package com.hatis.platform.identity.domain;

import com.hatis.platform.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An enrolled second factor.
 *
 * <p>The shared secret and the recovery codes are stored encrypted with the
 * user's tenant data key and are never returned after enrolment. Losing the
 * device is a support flow, not a reason to expose the secret through an API.
 */
@Entity
@Table(name = "idp_mfa_enrolments", indexes = {
        @Index(name = "ix_idp_mfa_user", columnList = "user_id")
})
public class MfaEnrolment extends BaseEntity {

    public enum Type {
        TOTP,
        WEBAUTHN
    }

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private Type type;

    @Column(name = "secret_ciphertext", nullable = false, length = 1024)
    private String secretCiphertext;

    @Column(name = "backup_codes_ciphertext", length = 8192)
    private String backupCodesCiphertext;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** Prevents replay of a code inside its own time step. */
    @Column(name = "last_used_code")
    private String lastUsedCode;

    @Column(name = "last_used_step")
    private Long lastUsedStep;

    protected MfaEnrolment() {
        super();
    }

    public MfaEnrolment(UUID userId, Type type, String secretCiphertext) {
        super();
        this.userId = userId;
        this.type = type;
        this.secretCiphertext = secretCiphertext;
    }

    public void verify() {
        this.verifiedAt = Instant.now();
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public void storeBackupCodes(String ciphertext) {
        this.backupCodesCiphertext = ciphertext;
    }

    /** Records the code that was accepted so the same step cannot be replayed. */
    public void markCodeUsed(String code, long timeStep) {
        this.lastUsedCode = code;
        this.lastUsedStep = timeStep;
    }

    public boolean isReplay(String code, long timeStep) {
        return lastUsedStep != null && lastUsedStep == timeStep
                && lastUsedCode != null && lastUsedCode.equals(code);
    }

    public UUID getUserId() {
        return userId;
    }

    public Type getType() {
        return type;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public String getBackupCodesCiphertext() {
        return backupCodesCiphertext;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }
}
