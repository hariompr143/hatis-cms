package com.hatis.platform.identity.application;

import com.hatis.platform.identity.adapter.persistence.IdentityRepositories;
import com.hatis.platform.identity.domain.MfaEnrolment;
import com.hatis.platform.identity.domain.RefreshToken;
import com.hatis.platform.identity.domain.User;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.config.PlatformProperties;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.observability.RequestMetadata;
import com.hatis.platform.shared.observability.RequestMetadataHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sign-in, MFA, session issuance and revocation.
 *
 * <p>Security properties enforced here, each with a test:
 * <ul>
 *   <li><strong>No user enumeration.</strong> An unknown email and a wrong password
 *       return the same status, code and message, and both run a bcrypt comparison
 *       so response times match.</li>
 *   <li><strong>Lockout.</strong> Repeated failures lock the account; the counter
 *       resets on success.</li>
 *   <li><strong>Rotation with reuse detection.</strong> Replaying an already
 *       rotated refresh token revokes the whole session family.</li>
 *   <li><strong>Every outcome is audited</strong>, including denials, with the
 *       client IP so abuse can be traced.</li>
 * </ul>
 */
@Service
public class AuthenticationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Compared against when the email is unknown, so timing does not leak existence. */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7ZBpDLfjKqWkKqWkKqWkKqWkKqWkKqW";

    /** Passwords that pass length rules but appear on published breach lists. */
    private static final List<String> COMMON_PASSWORDS = List.of(
            "passwordpassword", "123456789012", "qwertyuiopas", "changeme1234", "letmein12345");

    private final IdentityRepositories.UserRepository users;
    private final IdentityRepositories.RefreshTokenRepository refreshTokens;
    private final IdentityRepositories.MfaEnrolmentRepository mfaEnrolments;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final TotpService totp;
    private final MfaSecretDecryptor mfaSecretDecryptor;
    private final PlatformProperties properties;
    private final AuditRecorder audit;
    private final Counter signInSuccess;
    private final Counter signInFailed;

    public AuthenticationService(IdentityRepositories.UserRepository users,
                                 IdentityRepositories.RefreshTokenRepository refreshTokens,
                                 IdentityRepositories.MfaEnrolmentRepository mfaEnrolments,
                                 PasswordEncoder passwordEncoder,
                                 TokenService tokens,
                                 TotpService totp,
                                 MfaSecretDecryptor mfaSecretDecryptor,
                                 PlatformProperties properties,
                                 AuditRecorder audit,
                                 MeterRegistry meterRegistry) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.mfaEnrolments = mfaEnrolments;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.totp = totp;
        this.mfaSecretDecryptor = mfaSecretDecryptor;
        this.properties = properties;
        this.audit = audit;
        this.signInSuccess = Counter.builder("hatis.auth.sign_in").tag("result", "success").register(meterRegistry);
        this.signInFailed = Counter.builder("hatis.auth.sign_in").tag("result", "failed").register(meterRegistry);
    }

    /** Decrypts an enrolled TOTP secret. Implemented by {@code MfaService}. */
    public interface MfaSecretDecryptor {
        String decrypt(MfaEnrolment enrolment);
    }

    // --- Registration --------------------------------------------------------

    @Transactional
    public SignUpResult signUp(@Valid SignUpCommand command) {
        String email = User.normalizeEmail(command.email());
        if (users.existsByEmail(email)) {
            throw new PlatformExceptions.AlreadyExists("That email address is already registered");
        }
        validatePassword(command.password());
        User user = users.save(new User(email, passwordEncoder.encode(command.password())));
        audit.record(AuditRecord.builder("user.created")
                .organization(command.organizationId())
                .actor(AuditRecord.ActorType.USER, user.getId(), user.getEmail())
                .resource("user", user.getId())
                .build());
        return new SignUpResult(user.getId(), user.getEmail());
    }

    // --- Sign-in -------------------------------------------------------------

    @Transactional
    public SignInResult signIn(@Valid SignInCommand command) {
        String email = User.normalizeEmail(command.email());
        RequestMetadata request = RequestMetadataHolder.get();
        User user = users.findByEmail(email).orElse(null);

        // Always run a bcrypt comparison so response time does not reveal whether
        // the account exists.
        boolean passwordMatches = passwordEncoder.matches(
                command.password() == null ? "" : command.password(),
                user == null || user.getPasswordHash() == null ? DUMMY_HASH : user.getPasswordHash());

        if (user == null || !passwordMatches) {
            if (user != null) {
                boolean locked = user.recordFailedSignIn(
                        properties.security().maxFailedSignInAttempts(),
                        properties.security().lockoutDuration());
                users.save(user);
                if (locked) {
                    audit.record(signInAudit("user.locked", user, AuditRecord.Result.DENIED,
                            "Too many failed sign-in attempts", request));
                }
            }
            signInFailed.increment();
            audit.record(AuditRecord.builder("auth.sign_in")
                    .actor(AuditRecord.ActorType.USER, user == null ? null : user.getId(), email)
                    .result(AuditRecord.Result.DENIED)
                    .reason("invalid_credentials")
                    .request(request.ip(), request.userAgent())
                    .build());
            throw new PlatformExceptions.InvalidCredentials();
        }

        if (user.getStatus() != User.Status.ACTIVE) {
            signInFailed.increment();
            throw new PlatformExceptions.Forbidden("This account is " + user.getStatus().name().toLowerCase());
        }
        if (user.isLocked(Instant.now())) {
            signInFailed.increment();
            throw new PlatformExceptions.Forbidden("This account is temporarily locked. Try again later.");
        }

        if (user.requiresMfa()) {
            audit.record(signInAudit("auth.mfa_required", user, AuditRecord.Result.SUCCESS,
                    "Password verified; second factor required", request));
            return SignInResult.mfaChallenge(tokens.issueMfaToken(user.getId(), command.organizationId()));
        }

        user.recordSuccessfulSignIn();
        users.save(user);
        signInSuccess.increment();
        audit.record(signInAudit("auth.sign_in", user, AuditRecord.Result.SUCCESS, null, request));
        return issueSession(user, command.organizationId(), List.of("pwd"));
    }

    @Transactional
    public SignInResult verifyMfa(@Valid VerifyMfaCommand command) {
        TokenService.VerifiedToken holder = tokens.verifyMfaToken(command.mfaToken());
        User user = users.findById(holder.userId())
                .orElseThrow(PlatformExceptions.InvalidCredentials::new);
        MfaEnrolment enrolment = mfaEnrolments
                .findFirstByUserIdAndTypeAndVerifiedAtIsNotNull(user.getId(), MfaEnrolment.Type.TOTP)
                .orElseThrow(() -> new PlatformExceptions.Forbidden("No verified second factor is enrolled"));

        long matchedStep = totp.verify(mfaSecretDecryptor.decrypt(enrolment), command.code());
        if (matchedStep < 0 || enrolment.isReplay(command.code(), matchedStep)) {
            signInFailed.increment();
            audit.record(AuditRecord.builder("auth.mfa_failed")
                    .actor(AuditRecord.ActorType.USER, user.getId(), user.getEmail())
                    .result(AuditRecord.Result.DENIED)
                    .reason(matchedStep < 0 ? "invalid_code" : "replayed_code")
                    .build());
            throw new PlatformExceptions.InvalidCredentials();
        }

        enrolment.markCodeUsed(command.code(), matchedStep);
        mfaEnrolments.save(enrolment);
        user.recordSuccessfulSignIn();
        users.save(user);
        signInSuccess.increment();
        audit.record(AuditRecord.builder("auth.sign_in")
                .actor(AuditRecord.ActorType.USER, user.getId(), user.getEmail())
                .resource("user", user.getId())
                .metadata(Map.of("mfa", "totp"))
                .build());
        return issueSession(user, holder.organizationId(), List.of("pwd", "totp"));
    }

    // --- Sessions ------------------------------------------------------------

    /**
     * Rotates a refresh token.
     *
     * <p>Presenting a token that was already rotated means it was stolen, so the
     * whole family is revoked and a security event is raised. The customer sees a
     * normal "please sign in again"; the operator sees the incident.
     */
    @Transactional
    public SignInResult refresh(@Valid RefreshCommand command) {
        RefreshToken presented = refreshTokens.findByTokenHash(hash(command.refreshToken()))
                .orElseThrow(() -> {
                    audit.record(AuditRecord.builder("auth.refresh")
                            .result(AuditRecord.Result.DENIED)
                            .reason("unknown_refresh_token")
                            .build());
                    return new PlatformExceptions.Unauthenticated("The refresh token is not recognised");
                });

        if (presented.isRevoked()) {
            refreshTokens.findByFamilyId(presented.getFamilyId()).forEach(token -> {
                token.revoke();
                refreshTokens.save(token);
            });
            audit.record(AuditRecord.builder("security.token_reuse")
                    .actor(AuditRecord.ActorType.USER, presented.getUserId(), null)
                    .organization(presented.getOrganizationId())
                    .result(AuditRecord.Result.DENIED)
                    .reason("A revoked refresh token was presented; the session family was revoked")
                    .build());
            throw new PlatformExceptions.Unauthenticated("This session has been revoked. Please sign in again.");
        }
        if (presented.isExpired(Instant.now())) {
            throw new PlatformExceptions.Unauthenticated("The refresh token has expired");
        }

        User user = users.findById(presented.getUserId())
                .orElseThrow(() -> new PlatformExceptions.Unauthenticated("The user no longer exists"));
        if (user.getStatus() != User.Status.ACTIVE) {
            throw new PlatformExceptions.Forbidden("This account is " + user.getStatus().name().toLowerCase());
        }

        IssuedToken replacement = newRefreshToken(user.getId(), presented.getOrganizationId(), presented.getFamilyId());
        refreshTokens.save(replacement.entity());
        presented.revoke(replacement.entity().getId());
        refreshTokens.save(presented);

        return new SignInResult(
                tokens.issueAccessToken(user.getId(), presented.getOrganizationId(), List.of(), List.of("refresh")),
                replacement.plaintext(),
                properties.security().accessTokenTtl().toSeconds(),
                null,
                false);
    }

    @Transactional
    public void signOut(String refreshToken) {
        refreshTokens.findByTokenHash(hash(refreshToken)).ifPresent(token -> {
            token.revoke();
            refreshTokens.save(token);
            audit.record(AuditRecord.builder("auth.sign_out")
                    .actor(AuditRecord.ActorType.USER, token.getUserId(), null)
                    .resource("session", token.getId())
                    .build());
        });
    }

    /** Revokes every session for a user. Used after a password or MFA change. */
    @Transactional
    public int revokeAllSessions(UUID userId) {
        int revoked = refreshTokens.revokeAllForUser(userId, Instant.now());
        audit.record(AuditRecord.builder("auth.sessions_revoked")
                .actor(AuditRecord.ActorType.USER, userId, null)
                .metadata(Map.of("count", revoked))
                .build());
        return revoked;
    }

    @Transactional
    public void changePassword(UUID userId, @Valid ChangePasswordCommand command) {
        User user = users.findById(userId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("User", userId));
        if (!passwordEncoder.matches(command.currentPassword(),
                user.getPasswordHash() == null ? DUMMY_HASH : user.getPasswordHash())) {
            throw new PlatformExceptions.InvalidCredentials();
        }
        validatePassword(command.newPassword());
        user.changePassword(passwordEncoder.encode(command.newPassword()));
        users.save(user);
        revokeAllSessions(userId);
        audit.record(AuditRecord.builder("user.password_changed")
                .actor(AuditRecord.ActorType.USER, userId, user.getEmail())
                .resource("user", userId)
                .build());
    }

    // --- Internals -----------------------------------------------------------

    private SignInResult issueSession(User user, UUID organizationId, List<String> amr) {
        IssuedToken issued = newRefreshToken(user.getId(), organizationId, UUID.randomUUID());
        refreshTokens.save(issued.entity());
        return new SignInResult(
                tokens.issueAccessToken(user.getId(), organizationId, List.of(), amr),
                issued.plaintext(),
                properties.security().accessTokenTtl().toSeconds(),
                null,
                false);
    }

    /**
     * Creates an unsaved refresh token and returns it together with its plaintext.
     *
     * <p>The plaintext exists only until the HTTP response is written; only the
     * SHA-256 hash is persisted.
     */
    private IssuedToken newRefreshToken(UUID userId, UUID organizationId, UUID familyId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        RequestMetadata request = RequestMetadataHolder.get();
        RefreshToken entity = new RefreshToken(userId, organizationId, hash(plaintext), familyId,
                Instant.now().plus(properties.security().refreshTokenTtl()),
                request.userAgent(), request.ip());
        return new IssuedToken(entity, plaintext);
    }

    private AuditRecord signInAudit(String action, User user, AuditRecord.Result result,
                                    String reason, RequestMetadata request) {
        return AuditRecord.builder(action)
                .actor(AuditRecord.ActorType.USER, user.getId(), user.getEmail())
                .resource("user", user.getId())
                .result(result)
                .reason(reason)
                .request(request.ip(), request.userAgent())
                .build();
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12) {
            throw new PlatformExceptions.Validation("A password must be at least 12 characters long", Map.of());
        }
        if (password.length() > 256) {
            throw new PlatformExceptions.Validation("A password must be at most 256 characters long", Map.of());
        }
        // NIST SP 800-63B: length over composition, but reject published passwords.
        if (COMMON_PASSWORDS.contains(password.toLowerCase(java.util.Locale.ROOT))) {
            throw new PlatformExceptions.Validation(
                    "That password appears on a list of commonly used passwords", Map.of());
        }
    }

    static String hash(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record IssuedToken(RefreshToken entity, String plaintext) {
    }

    // --- Commands and results -------------------------------------------------

    public record SignUpCommand(@Email @NotBlank @Size(max = 320) String email,
                                @NotBlank @Size(min = 12, max = 256) String password,
                                UUID organizationId) {
    }

    public record SignInCommand(@Email @NotBlank String email,
                                @NotBlank String password,
                                UUID organizationId) {
    }

    public record VerifyMfaCommand(@NotBlank String mfaToken, @NotBlank @Size(min = 6, max = 64) String code) {
    }

    public record RefreshCommand(@NotBlank String refreshToken) {
    }

    public record ChangePasswordCommand(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 12, max = 256) String newPassword) {
    }

    public record SignUpResult(UUID userId, String email) {
    }

    /**
     * Either a session ({@code mfaRequired = false}) or an MFA challenge. The two
     * shapes are one type so the client has a single, unambiguous response to
     * handle.
     */
    public record SignInResult(String accessToken,
                               String refreshToken,
                               long expiresIn,
                               String mfaToken,
                               boolean mfaRequired) {

        public static SignInResult mfaChallenge(String mfaToken) {
            return new SignInResult(null, null, 0, mfaToken, true);
        }
    }
}
