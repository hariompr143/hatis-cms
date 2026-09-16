package com.hatis.platform.shared.event;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    @Query("""
            select o from OutboxEntry o
            where o.publishedAt is null
              and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            order by o.occurredAt asc
            """)
    List<OutboxEntry> findPending(@Param("now") Instant now, Pageable pageable);

    long countByPublishedAtIsNull();
}
