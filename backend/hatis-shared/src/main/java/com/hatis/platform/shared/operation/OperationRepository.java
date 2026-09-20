package com.hatis.platform.shared.operation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationRepository extends JpaRepository<Operation, UUID> {

    Optional<Operation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Operation> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    Optional<Operation> findByRequestIdempotencyKeyAndOrganizationId(String key, UUID organizationId);

    /** Operations whose worker lease expired: the worker died mid-flight. */
    @Query("""
            select o from Operation o
            where o.state = :state
              and o.leaseExpiresAt < :now
            """)
    List<Operation> findAbandoned(@Param("state") Operation.State state,
                                  @Param("now") Instant now,
                                  Pageable pageable);

    List<Operation> findByStateAndOrganizationId(Operation.State state, UUID organizationId, Pageable pageable);
}
