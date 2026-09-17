package com.hatis.platform.identity.application;

import com.hatis.platform.identity.domain.MfaEnrolment;
import com.hatis.platform.shared.secret.EncryptionService;
import org.springframework.stereotype.Component;

/**
 * Decrypts an enrolled TOTP secret with the owning tenant's data key.
 *
 * <p>Extracted from {@code MfaService} for one reason: {@code AuthenticationService}
 * needs this capability during sign-in, and {@code MfaService} needs
 * {@code AuthenticationService} to revoke sessions when a second factor is reset.
 * Implementing the interface on {@code MfaService} made that a circular bean
 * dependency that only surfaced when the context started. Keeping the decryption
 * here makes the graph acyclic without a proxy or a lazy injection.
 */
@Component
public class MfaSecretDecryptorImpl implements AuthenticationService.MfaSecretDecryptor {

    private final EncryptionService encryption;
    private final TenantKeyService tenantKeys;

    public MfaSecretDecryptorImpl(EncryptionService encryption, TenantKeyService tenantKeys) {
        this.encryption = encryption;
        this.tenantKeys = tenantKeys;
    }

    @Override
    public String decrypt(MfaEnrolment enrolment) {
        TenantKeyService.TenantKey key = tenantKeys.keyFor(enrolment.getUserId());
        return encryption.decryptWith(key.wrappedDek(), key.keyId(), enrolment.getSecretCiphertext());
    }
}
