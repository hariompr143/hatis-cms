package com.hatis.platform.identity.application;

import com.hatis.platform.shared.config.PlatformProperties;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.Secret;
import com.hatis.platform.shared.secret.SecretStore;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and verifies RS256 access tokens.
 *
 * <p>The signing key is read from the {@link SecretStore}, never from Git,
 * application configuration or environment defaults. A missing key is a startup
 * failure, not a silent fallback to an insecure one.
 *
 * <p>Verification pins issuer and audience: a token minted for another service
 * must not be accepted here even though the signature would validate.
 */
@Component
public class TokenService {

    public static final String CLAIM_ORGANIZATION = "org";
    public static final String CLAIM_SESSION = "sid";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_MFA = "amr";

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final String SECRET_PATH = "hatis/security/token-signing-key";
    private static final String AUDIENCE = "hatis-platform";

    private final SecretStore secretStore;
    private final PlatformProperties properties;

    private volatile RSAKey cachedKey;

    public TokenService(SecretStore secretStore, PlatformProperties properties) {
        this.secretStore = secretStore;
        this.properties = properties;
    }

    public String issueAccessToken(UUID userId, UUID organizationId, List<String> roles, List<String> amr) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.security().accessTokenTtl());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.security().getTokenIssuer())
                .subject(userId.toString())
                .audience(AUDIENCE)
                .claim(CLAIM_ORGANIZATION, organizationId == null ? null : organizationId.toString())
                .claim(CLAIM_SESSION, UUID.randomUUID().toString())
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_MFA, amr)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build();
        return sign(claims);
    }

    /** Short-lived token that only authorizes completing an MFA challenge. */
    public String issueMfaToken(UUID userId, UUID organizationId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.security().getTokenIssuer())
                .subject(userId.toString())
                .audience("hatis-mfa")
                .claim(CLAIM_ORGANIZATION, organizationId == null ? null : organizationId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .build();
        return sign(claims);
    }

    public VerifiedToken verify(String token) {
        return verify(token, AUDIENCE);
    }

    /** Verifies an MFA challenge token, which is bound to a different audience. */
    public VerifiedToken verifyMfaToken(String token) {
        return verify(token, "hatis-mfa");
    }

    private VerifiedToken verify(String token, String expectedAudience) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new RSASSAVerifier(publicKey()))) {
                throw new PlatformExceptions.Unauthenticated("The access token signature is invalid");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            String issuer = properties.security().getTokenIssuer();
            if (!issuer.equals(claims.getIssuer())) {
                throw new PlatformExceptions.Unauthenticated("The access token was issued by an unknown authority");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience)) {
                throw new PlatformExceptions.Unauthenticated("The access token was issued for a different service");
            }
            Instant expiry = claims.getExpirationTime() == null
                    ? Instant.EPOCH : claims.getExpirationTime().toInstant();
            if (expiry.isBefore(Instant.now())) {
                throw new PlatformExceptions.Unauthenticated("The access token has expired");
            }
            return new VerifiedToken(
                    UUID.fromString(claims.getSubject()),
                    parseUuid(claims.getStringClaim(CLAIM_ORGANIZATION)),
                    claims.getJWTID(),
                    claims.getStringClaim(CLAIM_SESSION),
                    stringList(claims.getClaim(CLAIM_ROLES)),
                    stringList(claims.getClaim(CLAIM_MFA)),
                    expiry);
        } catch (ParseException e) {
            throw new PlatformExceptions.Unauthenticated("The access token is malformed");
        } catch (PlatformExceptions.Unauthenticated e) {
            throw e;
        } catch (Exception e) {
            // Signature/verification failures must never leak their reason.
            log.debug("Token verification failed: {}", e.getMessage());
            throw new PlatformExceptions.Unauthenticated("The access token is not valid");
        }
    }

    private String sign(JWTClaimsSet claims) {
        try {
            RSAKey key = signingKey();
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID())
                    .type(com.nimbusds.jose.JOSEObjectType.JWT)
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(key.toPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new PlatformExceptions.OperationFailed("Unable to sign an access token");
        }
    }

    private RSAKey signingKey() {
        RSAKey key = cachedKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (cachedKey == null) {
                cachedKey = loadSigningKey();
            }
            return cachedKey;
        }
    }

    private RSAKey loadSigningKey() {
        Secret secret = secretStore.get(SECRET_PATH);
        if (secret.isEmpty()) {
            throw new PlatformExceptions.OperationFailed(
                    "No token signing key is configured at " + SECRET_PATH
                            + " in the '" + secretStore.name() + "' secret store");
        }
        String pem = secret.reveal().trim();
        secret.destroy();
        try {
            String base64 = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(),
                            java.math.BigInteger.valueOf(65537)));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(properties.security().getKeyId())
                    .build();
        } catch (Exception e) {
            throw new PlatformExceptions.OperationFailed("The configured token signing key could not be parsed");
        }
    }

    private RSAPublicKey publicKey() {
        try {
            return signingKey().toRSAPublicKey();
        } catch (Exception e) {
            throw new PlatformExceptions.OperationFailed("The token signing key has no public component");
        }
    }

    private static UUID parseUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object claim) {
        if (claim instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    public Map<String, Object> publicJwk() {
        try {
            return signingKey().toPublicJWK().toJSONObject();
        } catch (Exception e) {
            throw new PlatformExceptions.OperationFailed("Unable to publish the signing key");
        }
    }

    /** A validated access token. */
    public record VerifiedToken(UUID userId,
                                UUID organizationId,
                                String jti,
                                String sessionId,
                                List<String> roles,
                                List<String> authenticationMethods,
                                Instant expiresAt) {
    }
}
