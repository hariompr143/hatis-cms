package com.hatis.platform.deployment.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An attempt to run a release in an environment.
 *
 * <p>Long-running by nature: it is created in {@code PENDING}, associated with a
 * {@code plat_operations} row, and driven to a terminal state by the worker. The
 * API returns {@code 202 Accepted} with the operation id so a client can poll
 * instead of holding a request open for the duration of a rollout.
 */
@Entity
@Table(name = "dep_deployments", indexes = {
        @Index(name = "ix_dep_deployments_env", columnList = "organization_id,environment_id,status")
})
public class Deployment extends TenantScopedEntity {

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, ROLLED_BACK
    }

    public enum Strategy {
        ROLLING, RECREATE, BLUE_GREEN, CANARY
    }

    public enum Health {
        UNKNOWN, HEALTHY, DEGRADED, UNHEALTHY
    }

    @Column(name = "environment_id", nullable = false, updatable = false)
    private UUID environmentId;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "release_id", nullable = false, updatable = false)
    private UUID releaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 20)
    private Strategy strategy;

    @Column(name = "replicas", nullable = false)
    private int replicas;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status", nullable = false, length = 20)
    private Health healthStatus = Health.UNKNOWN;

    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    @Column(name = "created_by")
    private UUID createdBy;

    protected Deployment() {
        super();
    }

    public Deployment(UUID organizationId, UUID environmentId, UUID applicationId, UUID releaseId,
                      Strategy strategy, int replicas, UUID createdBy) {
        super(organizationId);
        if (environmentId == null || applicationId == null || releaseId == null) {
            throw new PlatformExceptions.Validation(
                    "environmentId, applicationId and releaseId are required", Map.of());
        }
        if (replicas < 0 || replicas > 100) {
            throw new PlatformExceptions.Validation(
                    "replicas must be between 0 and 100", Map.of("field", "replicas"));
        }
        this.environmentId = environmentId;
        this.applicationId = applicationId;
        this.releaseId = releaseId;
        this.strategy = strategy == null ? Strategy.ROLLING : strategy;
        this.replicas = replicas;
        this.status = Status.PENDING;
        this.createdBy = createdBy;
    }

    public void associateOperation(UUID operationId) {
        this.operationId = operationId;
    }

    public void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
    }

    public void markCompleted(Health health) {
        this.status = Status.COMPLETED;
        this.healthStatus = health == null ? Health.UNKNOWN : health;
        this.finishedAt = Instant.now();
    }

    public void markFailed(Health health) {
        this.status = Status.FAILED;
        this.healthStatus = health == null ? Health.UNHEALTHY : health;
        this.finishedAt = Instant.now();
    }

    public void cancel() {
        if (status == Status.COMPLETED || status == Status.ROLLED_BACK) {
            throw new PlatformExceptions.StateConflict("A finished deployment cannot be cancelled");
        }
        this.status = Status.CANCELLED;
        this.finishedAt = Instant.now();
    }

    public void markRolledBack() {
        this.status = Status.ROLLED_BACK;
        this.rolledBackAt = Instant.now();
        this.finishedAt = Instant.now();
    }

    public void recordHealth(Health health) {
        this.healthStatus = health == null ? Health.UNKNOWN : health;
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED
                || status == Status.CANCELLED || status == Status.ROLLED_BACK;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public Status getStatus() {
        return status;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public int getReplicas() {
        return replicas;
    }

    public Health getHealthStatus() {
        return healthStatus;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
