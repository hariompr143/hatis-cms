package com.hatis.platform.domains.adapter.persistence;

import com.hatis.platform.domains.domain.Domain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DomainRepository extends JpaRepository<Domain, UUID> {

    Optional<Domain> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndHostname(UUID organizationId, String hostname);

    /** A hostname may be bound by at most one tenant platform-wide. */
    boolean existsByHostname(String hostname);

    List<Domain> findByOrganizationIdAndProjectIdAndStatusNot(UUID organizationId,
                                                              UUID projectId,
                                                              Domain.Status status);

    List<Domain> findByStatus(Domain.Status status);
}
