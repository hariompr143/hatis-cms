package com.hatis.platform.identity.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * A human principal.
 *
 * <p>Users are platform-wide, not tenant-scoped: one person can belong to several
 * organizations. The tenant relationship lives in {@code org_memberships}.
 *
 * <p>Sign-in hardening lives on this aggregate because it is a property of the
 * credential, not of a request: failure counting, lockout and forced re-auth
 * after a credential change.
 */
@Entity
@Table(name = "idp_users", indexes = {
        @Index(name = "ix_idp_users_email", columnList = "email", unique = true)
})
public class User extends BaseEntity {

    public enum Status {
        PENDING,
        ACTIVE,
        SUSPENDED,
        DELETED
    }

    public enum MfaStatus {
        DISABLED,
        ENROLLED,
        REQUIRED
    }

        // citext, not varchar: the unique index on email is case-insensitive and that is
    // load-bearing. columnDefinition carries the exact type name so that
    // ddl-auto: validate accepts it; without it a String expects varchar.
    @Column(name = "email", nullable = false, length = 320, updatable = false,
            columnDefinition = "citext")
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    /** bcrypt hash. Never logged, never serialized, never returned by an API. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "password_updated_at")
    private Instant passwordUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "mfa_status", nullable = false, length = 20)
    private MfaStatus mfaStatus;

    @Column(name = "last_sign_in_at")
    private Instant lastSignInAt;

    @Column(name = "failed_sign_in_count", nullable = false)
    private int failedSignInCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Bumped whenever credentials change, so cached authorizations can be invalidated. */
    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {
        super();
    }

    public User(String email, String passwordHash) {
        super();
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.passwordUpdatedAt = Instant.now();
        this.status = Status.ACTIVE;
        this.mfaStatus = MfaStatus.DISABLED;
        this.failedSignInCount = 0;
        this.credentialVersion = 1;
    }

    public void recordSuccessfulSignIn() {
        this.lastSignInAt = Instant.now();
        this.failedSignInCount = 0;
        this.lockedUntil = null;
    }

    /**
     * Records a failed attempt and locks the account when the threshold is passed.
     *
     * @return true when this call caused the account to become locked
     */
    public boolean recordFailedSignIn(int maxAttempts, Duration lockout) {
        this.failedSignInCount++;
        if (this.failedSignInCount >= maxAttempts) {
            this.lockedUntil = Instant.now().plus(lockout);
            this.failedSignInCount = 0;
            return true;
        }
        return false;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.passwordUpdatedAt = Instant.now();
        this.credentialVersion++;
        this.failedSignInCount = 0;
        this.lockedUntil = null;
    }

    public void markMfaEnrolled() {
        this.mfaStatus = MfaStatus.ENROLLED;
        this.credentialVersion++;
    }

    public void requireMfa() {
        this.mfaStatus = MfaStatus.REQUIRED;
    }

    public void disableMfa() {
        this.mfaStatus = MfaStatus.DISABLED;
        this.credentialVersion++;
    }

    public void markEmailVerified() {
        this.emailVerified = true;
    }

    public void suspend() {
        this.status = Status.SUSPENDED;
        this.credentialVersion++;
    }

    public void activate() {
        this.status = Status.ACTIVE;
    }

    public void delete() {
        this.status = Status.DELETED;
        this.deletedAt = Instant.now();
        this.passwordHash = null;
        this.credentialVersion++;
    }

    public boolean canSignIn() {
        return status == Status.ACTIVE && passwordHash != null;
    }

    public boolean requiresMfa() {
        return mfaStatus == MfaStatus.ENROLLED || mfaStatus == MfaStatus.REQUIRED;
    }

    /** Emails are matched case-insensitively; the stored form is lowercased. */
    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 320 || !email.contains("@")) {
            throw new PlatformExceptions.Validation("A valid email address is required", java.util.Map.of());
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getPasswordUpdatedAt() {
        return passwordUpdatedAt;
    }

    public Status getStatus() {
        return status;
    }

    public MfaStatus getMfaStatus() {
        return mfaStatus;
    }

    public Instant getLastSignInAt() {
        return lastSignInAt;
    }

    public int getFailedSignInCount() {
        return failedSignInCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public long getCredentialVersion() {
        return credentialVersion;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
