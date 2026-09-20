package com.hatis.platform.identity.application;

import com.hatis.platform.identity.adapter.persistence.UserRepository;
import com.hatis.platform.identity.adapter.persistence.MfaEnrolmentRepository;
import com.hatis.platform.identity.domain.MfaEnrolment;
import com.hatis.platform.identity.domain.User;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.EncryptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * TOTP enrolment and verification.
 *
 * <p>The shared secret is encrypted with the tenant data key before it touches the
 * database and is decrypted only for the few microseconds needed to verify a code.
 * It is returned to the user exactly once, during enrolment; there is no "show my
 * secret again" endpoint, because there cannot be one.
 */
@Service
public class MfaService {

    private static final int RECOVERY_CODE_COUNT = 10;

    private final MfaEnrolmentRepository enrolments;
    private final UserRepository users;
    private final TotpService totp;
    private final EncryptionService encryption;
    private final TenantKeyService tenantKeys;
    private final AuthenticationService.MfaSecretDecryptor decryptor;
    private final AuthenticationService authentication;
    private final AuditRecorder audit;

    public MfaService(MfaEnrolmentRepository enrolments,
                      UserRepository users,
                      TotpService totp,
                      EncryptionService encryption,
                      TenantKeyService tenantKeys,
                      MfaSecretDecryptorImpl decryptor,
                      AuthenticationService authentication,
                      AuditRecorder audit) {
        this.enrolments = enrolments;
        this.users = users;
        this.totp = totp;
        this.encryption = encryption;
        this.tenantKeys = tenantKeys;
        this.decryptor = decryptor;
        this.authentication = authentication;
        this.audit = audit;
    }

    /** Decrypts an enrolled secret. Delegates so the decryption lives in one place. */
    public String decrypt(MfaEnrolment enrolment) {
        return decryptor.decrypt(enrolment);
    }

    /**
     * Starts enrolment. Returns the provisioning URI and secret once.
     *
     * <p>The enrolment is not usable until {@link #verify} succeeds, so a
     * half-finished enrolment can never lock a user out.
     */
    @Transactional
    public EnrolmentChallenge beginEnrolment(UUID userId, String issuer) {
        User user = users.findById(userId).orElseThrow(() -> new PlatformExceptions.NotFound("User", userId));
        enrolments.findFirstByUserIdAndTypeAndVerifiedAtIsNotNull(userId, MfaEnrolment.Type.TOTP)
                .ifPresent(existing -> {
                    throw new PlatformExceptions.AlreadyExists("A second factor is already enrolled");
                });

        String secret = totp.generateSecret();
        TenantKeyService.TenantKey key = tenantKeys.keyFor(userId);
        MfaEnrolment enrolment = enrolments.save(new MfaEnrolment(
                userId, MfaEnrolment.Type.TOTP,
                encryption.encryptWith(key.wrappedDek(), key.keyId(), secret)));

        audit.record(AuditRecord.builder("mfa.enrolment_started")
                .actor(AuditRecord.ActorType.USER, userId, user.getEmail())
                .resource("mfa_enrolment", enrolment.getId())
                .build());
        return new EnrolmentChallenge(enrolment.getId(), totp.otpauthUri(issuer, user.getEmail(), secret), secret);
    }

    /**
     * Completes enrolment and returns single-use recovery codes.
     *
     * <p>Recovery codes are hashed individually: losing the database must not give
     * an attacker working recovery codes.
     */
    @Transactional
    public EnrolmentResult verify(UUID userId, UUID enrolmentId, String code) {
        MfaEnrolment enrolment = enrolments.findById(enrolmentId)
                .filter(e -> e.getUserId().equals(userId))
                .orElseThrow(() -> new PlatformExceptions.NotFound("MFA enrolment", enrolmentId));
        if (enrolment.isVerified()) {
            throw new PlatformExceptions.StateConflict("This enrolment is already verified");
        }

        long step = totp.verify(decrypt(enrolment), code);
        if (step < 0) {
            audit.record(AuditRecord.builder("mfa.enrolment_failed")
                    .actor(AuditRecord.ActorType.USER, userId, null)
                    .result(AuditRecord.Result.DENIED)
                    .reason("invalid_code")
                    .build());
            throw new PlatformExceptions.BusinessRuleViolation("That code is not valid");
        }

        List<String> recoveryCodes = totp.generateRecoveryCodes(RECOVERY_CODE_COUNT);
        TenantKeyService.TenantKey key = tenantKeys.keyFor(userId);
        String encodedCodes = String.join("\n", recoveryCodes);
        enrolment.storeBackupCodes(
                encryption.encryptWith(key.wrappedDek(), key.keyId(), encodedCodes));
        enrolment.markCodeUsed(code, step);
        enrolment.verify();
        enrolments.save(enrolment);

        users.findById(userId).ifPresent(user -> {
            user.markMfaEnrolled();
            users.save(user);
        });

        // Enrolling MFA changes the account's security posture, so existing
        // sessions are revoked and the user must authenticate again with both factors.
        authentication.revokeAllSessions(userId);

        audit.record(AuditRecord.builder("mfa.enrolled")
                .actor(AuditRecord.ActorType.USER, userId, null)
                .resource("mfa_enrolment", enrolmentId)
                .build());
        return new EnrolmentResult(recoveryCodes);
    }

    @Transactional
    public void disable(UUID userId) {
        MfaEnrolment enrolment = enrolments
                .findFirstByUserIdAndTypeAndVerifiedAtIsNotNull(userId, MfaEnrolment.Type.TOTP)
                .orElseThrow(() -> new PlatformExceptions.NotFound("No second factor is enrolled"));
        enrolments.delete(enrolment);
        users.findById(userId).ifPresent(user -> {
            user.disableMfa();
            users.save(user);
        });
        authentication.revokeAllSessions(userId);
        audit.record(AuditRecord.builder("mfa.disabled")
                .actor(AuditRecord.ActorType.USER, userId, null)
                .result(AuditRecord.Result.SUCCESS)
                .reason("Second factor removed; all sessions revoked")
                .build());
    }

    public record EnrolmentChallenge(UUID enrolmentId, String otpauthUri, String secret) {
    }

    public record EnrolmentResult(List<String> recoveryCodes) {
    }
}
