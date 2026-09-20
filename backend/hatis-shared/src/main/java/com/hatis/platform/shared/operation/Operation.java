package com.hatis.platform.shared.operation;

import com.hatis.platform.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A long-running, observable unit of asynchronous work.
 *
 * <p>Provisioning a database, issuing a certificate, building a release or
 * deploying a workload all return {@code 202 Accepted} plus an operation id. The
 * row is written <em>before</em> the work starts, in the same transaction as the
 * API call, so a customer can always ask what happened — including when the
 * worker crashes.
 */
@Entity
@Table(name = "plat_operations", indexes = {
        @Index(name = "ix_plat_operations_org_state", columnList = "organization_id,state"),
        @Index(name = "ix_plat_operations_lease", columnList = "state,lease_expires_at")
})
public class Operation extends BaseEntity {

    public enum State {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "kind", nullable = false, length = 80)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private State state = State.PENDING;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "resource_type", length = 80)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "request_idempotency_key", length = 128)
    private String requestIdempotencyKey;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Lease used by the worker reaper: an expired lease means the worker died. */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Operation() {
        super();
    }

    public Operation(UUID organizationId, String kind, String resourceType, UUID resourceId) {
        super();
        this.organizationId = organizationId;
        this.kind = kind;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.state = State.PENDING;
        this.progress = 0;
        this.attempts = 0;
    }

    public void markRunning(int progressPercent, java.time.Duration lease) {
        this.state = State.RUNNING;
        this.progress = Math.max(0, Math.min(100, progressPercent));
        this.startedAt = this.startedAt == null ? Instant.now() : this.startedAt;
        this.leaseExpiresAt = Instant.now().plus(lease);
        this.attempts++;
    }

    public void updateProgress(int progressPercent) {
        this.progress = Math.max(0, Math.min(100, progressPercent));
        this.leaseExpiresAt = Instant.now().plusSeconds(300);
    }

    public void markSucceeded() {
        this.state = State.SUCCESS;
        this.progress = 100;
        this.finishedAt = Instant.now();
        this.leaseExpiresAt = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void markFailed(String code, String message) {
        this.state = State.FAILED;
        this.finishedAt = Instant.now();
        this.leaseExpiresAt = null;
        this.errorCode = code;
        this.errorMessage = message == null ? null
                : message.substring(0, Math.min(message.length(), 2000));
    }

    public void markCancelled() {
        this.state = State.CANCELLED;
        this.finishedAt = Instant.now();
        this.leaseExpiresAt = null;
    }

    public boolean isTerminal() {
        return state == State.SUCCESS || state == State.FAILED || state == State.CANCELLED;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getKind() {
        return kind;
    }

    public State getState() {
        return state;
    }

    public int getProgress() {
        return progress;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getRequestIdempotencyKey() {
        return requestIdempotencyKey;
    }

    public void setRequestIdempotencyKey(String requestIdempotencyKey) {
        this.requestIdempotencyKey = requestIdempotencyKey;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }
}
