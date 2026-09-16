package com.hatis.platform.identity.adapter.persistence;

import com.hatis.platform.identity.domain.ApiKey;
import com.hatis.platform.identity.domain.MfaEnrolment;
import com.hatis.platform.identity.domain.RefreshToken;
import com.hatis.platform.identity.domain.ServiceAccount;
import com.hatis.platform.identity.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapters for the identity context.
 *
 * <p>{@code findByEmail} is intentionally the only unscoped lookup in the whole
 * platform: authentication has to find a user before a tenant is known. Everything
 * after sign-in is tenant-scoped.
 */
public class IdentityRepositories {

    public interface UserRepository extends JpaRepository<User, UUID> {
        Optional<User> findByEmail(String email);

        boolean existsByEmail(String email);
    }

    public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
        Optional<RefreshToken> findByTokenHash(String tokenHash);

        List<RefreshToken> findByFamilyId(UUID familyId);

        List<RefreshToken> findByUserId(UUID userId);

        @Modifying
        @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
        int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

        @Modifying
        @Query("delete from RefreshToken t where t.expiresAt < :now")
        int deleteExpired(@Param("now") Instant now);
    }

    public interface MfaEnrolmentRepository extends JpaRepository<MfaEnrolment, UUID> {
        Optional<MfaEnrolment> findFirstByUserIdAndTypeAndVerifiedAtIsNotNull(UUID userId, MfaEnrolment.Type type);

        List<MfaEnrolment> findByUserId(UUID userId);

        void deleteByUserId(UUID userId);
    }

    public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
        Optional<ApiKey> findByKeyHash(String keyHash);

        Optional<ApiKey> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<ApiKey> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

        List<ApiKey> findByOrganizationId(UUID organizationId);

        long countByOrganizationIdAndRevokedAtIsNull(UUID organizationId);
    }

    public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {
        Optional<ServiceAccount> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<ServiceAccount> findByOrganizationId(UUID organizationId);
    }
}
