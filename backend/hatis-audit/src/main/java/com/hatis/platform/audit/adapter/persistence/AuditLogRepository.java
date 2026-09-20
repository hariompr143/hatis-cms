package com.hatis.platform.audit.adapter.persistence;

import com.hatis.platform.audit.domain.AuditLog;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByOrganizationIdOrderByOccurredAtDesc(UUID organizationId, Pageable pageable);

    @Query("""
            select a from AuditLog a
            where a.organizationId = :organizationId
              and (:action is null or a.action = :action)
              and (:actorId is null or a.actorId = :actorId)
              and (:from is null or a.occurredAt >= :from)
              and (:to is null or a.occurredAt <= :to)
            order by a.occurredAt desc
            """)
    Page<AuditLog> search(@Param("organizationId") UUID organizationId,
                          @Param("action") String action,
                          @Param("actorId") UUID actorId,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);

    /** Newest row of a tenant's chain; used to link the next record. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from AuditLog a
            where a.organizationId = :organizationId
            order by a.sequence desc
            """)
    List<AuditLog> findChainHead(@Param("organizationId") UUID organizationId, Pageable pageable);

    Optional<AuditLog> findFirstByOrganizationIdOrderBySequenceAsc(UUID organizationId);

    List<AuditLog> findByOrganizationIdOrderBySequenceAsc(UUID organizationId);

    long countByOrganizationIdAndResult(UUID organizationId,
                                        com.hatis.platform.shared.audit.AuditRecord.Result result);
}
