package com.hatis.platform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable audit record.
 *
 * <p>Rows are append-only: the application role has no {@code UPDATE} or
 * {@code DELETE} grant on this table. Each row carries the hash of the previous
 * row for its tenant, so removing or editing a record breaks the chain and the
 * tamper is detectable by {@code AuditChainVerifier}.
 */
@Entity
@Table(name = "aud_audit_logs", indexes = {
        @Index(name = "ix_aud_logs_org_time", columnList = "organization_id,occurred_at"),
        @Index(name = "ix_aud_logs_org_action", columnList = "organization_id,action"),
        @Index(name = "ix_aud_logs_resource", columnList = "resource_type,resource_id")
})
public class AuditLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 32)
    private com.hatis.platform.shared.audit.AuditRecord.ActorType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_email", length = 320)
    private String actorEmail;

    @Column(name = "action", nullable = false, length = 120)
    private String action;

    @Column(name = "resource_type", length = 80)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private com.hatis.platform.shared.audit.AuditRecord.Result result;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Redacted, non-secret context. Never contains credentials or tokens. */
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    // The migration declares these char(64), not varchar. Without columnDefinition
    // Hibernate infers varchar and, with ddl-auto: validate, refuses to start.
    @Column(name = "previous_hash", length = 64, columnDefinition = "char(64)")
    private String previousHash;

    @Column(name = "record_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String recordHash;

    @Column(name = "sequence", nullable = false)
    private long sequence;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditLog() {
    }

    public AuditLog(UUID id,
                    UUID organizationId,
                    com.hatis.platform.shared.audit.AuditRecord.ActorType actorType,
                    UUID actorId,
                    String actorEmail,
                    String action,
                    String resourceType,
                    UUID resourceId,
                    com.hatis.platform.shared.audit.AuditRecord.Result result,
                    String reason,
                    String ip,
                    String userAgent,
                    String correlationId,
                    String metadata,
                    String previousHash,
                    String recordHash,
                    long sequence,
                    Instant occurredAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.result = result;
        this.reason = reason;
        this.ip = ip;
        this.userAgent = userAgent;
        this.correlationId = correlationId;
        this.metadata = metadata;
        this.previousHash = previousHash;
        this.recordHash = recordHash;
        this.sequence = sequence;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public com.hatis.platform.shared.audit.AuditRecord.ActorType getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public com.hatis.platform.shared.audit.AuditRecord.Result getResult() {
        return result;
    }

    public String getReason() {
        return reason;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getRecordHash() {
        return recordHash;
    }

    public long getSequence() {
        return sequence;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
