package com.hatis.platform.integration.adapter.persistence;

import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt-set of posting one event to one endpoint.
 *
 * <p>Maps to {@code int_webhook_deliveries}. This is the record that makes "where did my
 * webhook go?" answerable: a customer reporting a missing delivery needs the status, the
 * response their server returned and the time of the last attempt, not an assurance that
 * the platform tried.
 *
 * <p>The row is mutable — {@code attempts} increases, {@code status} advances and
 * {@code next_attempt_at} is rescheduled — which is why {@code V1_014} gave it the
 * {@code updated_at} and {@code version} columns the rest of the tenant-owned tables
 * have. Optimistic locking is not decorative here either: a delivery whose worker died
 * mid-attempt must not be silently overwritten by a second worker.
 */
@Entity
@Table(name = "int_webhook_deliveries")
public class WebhookDelivery extends TenantScopedEntity {

    /** Mirrors the check constraint on {@code int_webhook_deliveries.status}. */
    public enum Status {
        PENDING,
        DELIVERED,
        FAILED,
        SKIPPED
    }

    /** The column is {@code varchar(1000)}; a stack trace must not fail the insert. */
    private static final int MAX_ERROR_LENGTH = 1000;

    @Column(name = "endpoint_id", nullable = false, updatable = false)
    private UUID endpointId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 120)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected WebhookDelivery() {
        super();
    }

    private WebhookDelivery(UUID organizationId, UUID endpointId, UUID eventId, String eventType) {
        super(organizationId);
        this.endpointId = endpointId;
        this.eventId = eventId;
        this.eventType = eventType;
    }

    public static WebhookDelivery pending(UUID organizationId, UUID endpointId,
                                          UUID eventId, String eventType) {
        if (endpointId == null) {
            throw new IllegalArgumentException("endpointId is required for a delivery");
        }
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required for a delivery");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required for a delivery");
        }
        return new WebhookDelivery(organizationId, endpointId, eventId, eventType);
    }

    /** Records a delivery the endpoint accepted. */
    public void recordDelivered(int responseStatus, Instant deliveredAt) {
        this.status = Status.DELIVERED;
        this.responseStatus = responseStatus;
        this.deliveredAt = deliveredAt;
        this.attempts++;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    /**
     * Records an attempt that did not succeed.
     *
     * @param exhausted whether the retry budget is spent; when it is, the delivery
     *                  becomes FAILED rather than staying PENDING for another attempt
     */
    public void recordFailure(int responseStatusOrNull, String error,
                              Instant nextAttemptOrNull, boolean exhausted) {
        this.attempts++;
        this.responseStatus = responseStatusOrNull == 0 ? null : responseStatusOrNull;
        this.lastError = truncate(error);
        if (exhausted) {
            this.status = Status.FAILED;
            this.nextAttemptAt = null;
        } else {
            this.nextAttemptAt = nextAttemptOrNull;
        }
    }

    /**
     * Records that the platform chose not to deliver, rather than that it tried and
     * failed. An endpoint that is paused, or a URL that now resolves to a private
     * address, is a SKIPPED delivery — retrying it would be pointless and would hide
     * the reason from the customer.
     */
    public void recordSkipped(String reason) {
        this.status = Status.SKIPPED;
        this.lastError = truncate(reason);
        this.nextAttemptAt = null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }
}
