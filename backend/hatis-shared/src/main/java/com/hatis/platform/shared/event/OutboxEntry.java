package com.hatis.platform.shared.event;

import com.hatis.platform.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox row.
 *
 * <p>Written inside the business transaction and drained asynchronously by
 * {@code OutboxRelay}. This is what makes "the customer always sees the effect of
 * a committed change" true even when Kafka is down.
 */
@Entity
@Table(name = "plat_outbox")
public class OutboxEntry extends BaseEntity {

    @Column(name = "aggregate_type", nullable = false, length = 80)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** JSON serialisation of {@link PlatformEvent#data()}. */
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    protected OutboxEntry() {
        super();
    }

    public OutboxEntry(PlatformEvent event, String payloadJson) {
        super();
        this.aggregateType = event.resourceType() == null ? "platform" : event.resourceType();
        this.aggregateId = event.resourceId();
        this.eventType = event.eventType();
        this.eventVersion = event.eventVersion();
        this.organizationId = event.organizationId();
        this.correlationId = event.correlationId();
        this.occurredAt = event.occurredAt();
        this.payload = payloadJson;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    /** Exponential backoff capped at five minutes so a dead broker cannot stall the relay forever. */
    public void markFailedAttempt() {
        this.attempts++;
        long delaySeconds = Math.min(300L, (long) Math.pow(2, Math.min(attempts, 8)));
        this.nextAttemptAt = Instant.now().plusSeconds(delaySeconds);
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getPayload() {
        return payload;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
