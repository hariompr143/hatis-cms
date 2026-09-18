package com.hatis.platform.shared.event;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Access to the transactional outbox.
 *
 * <p>There is deliberately no "everything pending, whichever tenant it belongs to" query
 * here, and the one that used to exist was removed. {@code plat_outbox} carries forced row
 * level security, so such a query run without a tenant bound returns nothing at all: it
 * does not fail loudly, it returns an empty list, and the relay that used it published no
 * event for as long as it existed. {@code OutboxRelayRlsIT#anUnboundQueryStillSeesNoTenantOwnedRows}
 * is the test that keeps that behaviour from being mistaken for a working query again.
 *
 * <p>Work is therefore claimed one tenant at a time, plus one pass for the rows that
 * belong to no tenant. Both queries return identifiers rather than entities so the read
 * transaction can close before anything is sent: publishing holds no locks while a sink
 * is slow.
 */
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * Unpublished entries of one organization whose backoff has elapsed.
     *
     * <p>The {@code organizationId} predicate is not redundant with row level security.
     * RLS is the layer that holds when a caller forgets; the predicate is what makes the
     * intent explicit and keeps this query correct if it is ever reached from a session
     * bound to a different tenant.
     */
    @Query("""
            select o.id from OutboxEntry o
            where o.organizationId = :organizationId
              and o.publishedAt is null
              and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            order by o.occurredAt asc
            """)
    List<UUID> findPendingIds(@Param("organizationId") UUID organizationId,
                              @Param("now") Instant now,
                              Pageable pageable);

    /**
     * Unpublished platform-wide entries — the ones with no organization at all.
     *
     * <p>Readable and updatable by a session with no tenant bound, and by no tenant
     * session in a way that would let one claim another's work; see
     * {@code V1_016__outbox_platform_publish.sql} for why the read and write policies
     * differ.
     */
    @Query("""
            select o.id from OutboxEntry o
            where o.organizationId is null
              and o.publishedAt is null
              and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            order by o.occurredAt asc
            """)
    List<UUID> findPendingPlatformIds(@Param("now") Instant now, Pageable pageable);

    long countByPublishedAtIsNull();
}
