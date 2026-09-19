package com.hatis.platform.identity.adapter.persistence;
import com.hatis.platform.identity.domain.ApiKey;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** API key persistence. Only hashes are stored; lookups are by hash. */
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    Optional<ApiKey> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ApiKey> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    List<ApiKey> findByOrganizationId(UUID organizationId);

    long countByOrganizationIdAndRevokedAtIsNull(UUID organizationId);
}
