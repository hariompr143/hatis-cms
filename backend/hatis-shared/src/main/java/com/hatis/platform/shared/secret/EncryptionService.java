package com.hatis.platform.shared.secret;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Envelope encryption for tenant data at rest.
 *
 * <p>Each organization gets its own data encryption key (DEK). The DEK is
 * generated locally, wrapped by a key encryption key (KEK) held in the configured
 * {@link SecretStore}, and the wrapped form is what the platform database stores.
 *
 * <p>Consequences that matter to customers:
 * <ul>
 *   <li>A database dump contains no usable key material.</li>
 *   <li>Rotating the KEK unwraps and rewraps DEKs without re-encrypting data.</li>
 *   <li>Revoking one tenant's DEK cryptographically erases that tenant's data.</li>
 * </ul>
 *
 * <p>Cipher: AES-256-GCM with a random 96-bit nonce per encryption; the nonce is
 * prepended to the ciphertext so it never has to be stored separately.
 */
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final String KEK_PATH_PREFIX = "hatis/kek/";

    private final SecretStore secretStore;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, SecretKey> kekCache = new ConcurrentHashMap<>();

    public EncryptionService(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    /** Generates a fresh 256-bit DEK for a tenant. */
    public byte[] generateDataKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
            generator.init(256, random);
            return generator.generateKey().getEncoded();
        } catch (GeneralSecurityException e) {
            throw new PlatformExceptions.OperationFailed("Unable to generate a data encryption key");
        }
    }

    /** Wraps a DEK with the tenant KEK so it can be stored in the database. */
    public String wrapDataKey(String keyId, byte[] dataKey) {
        SecretKey kek = kek(keyId);
        return Base64.getEncoder().encodeToString(encrypt(kek, dataKey));
    }

    public byte[] unwrapDataKey(String keyId, String wrapped) {
        SecretKey kek = kek(keyId);
        return decrypt(kek, Base64.getDecoder().decode(wrapped));
    }

    /** Encrypts a tenant value with the tenant DEK. Returns base64(nonce || ciphertext || tag). */
    public String encryptWith(String wrappedDek, String keyId, String plaintext) {
        byte[] dek = unwrapDataKey(keyId, wrappedDek);
        try {
            return Base64.getEncoder().encodeToString(encrypt(new SecretKeySpec(dek, ALGORITHM),
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } finally {
            java.util.Arrays.fill(dek, (byte) 0);
        }
    }

    public String decryptWith(String wrappedDek, String keyId, String ciphertext) {
        byte[] dek = unwrapDataKey(keyId, wrappedDek);
        try {
            return new String(decrypt(new SecretKeySpec(dek, ALGORITHM),
                    Base64.getDecoder().decode(ciphertext)), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            java.util.Arrays.fill(dek, (byte) 0);
        }
    }

    private SecretKey kek(String keyId) {
        return kekCache.computeIfAbsent(keyId, id -> {
            Secret stored = secretStore.get(KEK_PATH_PREFIX + id);
            if (stored.isEmpty()) {
                // First use for this key id: create the KEK and persist it in the
                // secret store. The database never sees the plaintext KEK.
                byte[] material = generateDataKey();
                Secret fresh = Secret.of(Base64.getEncoder().encodeToString(material));
                secretStore.put(KEK_PATH_PREFIX + id, fresh);
                java.util.Arrays.fill(material, (byte) 0);
                return new SecretKeySpec(Base64.getDecoder().decode(fresh.reveal()), ALGORITHM);
            }
            return new SecretKeySpec(Base64.getDecoder().decode(stored.reveal()), ALGORITHM);
        });
    }

    private byte[] encrypt(SecretKey key, byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new PlatformExceptions.OperationFailed("Encryption failed");
        }
    }

    private byte[] decrypt(SecretKey key, byte[] payload) {
        if (payload.length <= NONCE_BYTES) {
            throw new PlatformExceptions.BusinessRuleViolation("Ciphertext is truncated");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            // GCM authentication failure means tampering or the wrong key. Never
            // include the reason in the response.
            throw new PlatformExceptions.BusinessRuleViolation("Decryption failed");
        }
    }
}
