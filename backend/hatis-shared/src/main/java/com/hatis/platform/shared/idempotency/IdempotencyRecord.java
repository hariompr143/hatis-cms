package com.hatis.platform.shared.idempotency;

import com.hatis.platform.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A remembered mutating request.
 *
 * <p>Any API that creates financial, infrastructure or deployment resources must
 * accept an {@code Idempotency-Key}. When the same key arrives twice the original
 * response is replayed and no second resource is created — which is what makes a
 * retrying customer pipeline safe.
 *
 * <p>The unique constraint is on {@code (organization_id, key)}: keys are scoped
 * per tenant so one customer's key cannot collide with another's.
 */
@Entity
@Table(name = "plat_idempotency_keys", indexes = {
        @Index(name = "ix_plat_idempotency_expires", columnList = "expires_at")
})
public class IdempotencyRecord extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "key", nullable = false, length = 128, updatable = false)
    private String key;

    @Column(name = "method", nullable = false, length = 10, updatable = false)
    private String method;

    @Column(name = "path", nullable = false, length = 512, updatable = false)
    private String path;

    /** SHA-256 of the request body; a reused key with a different body is a client bug. */
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
        super();
    }

    public IdempotencyRecord(UUID organizationId,
                             String key,
                             String method,
                             String path,
                             String requestHash,
                     Instant expiresAt) {
        super();
        this.organizationId = organizationId;
        this.key = key;
        this.method = method;
        this.path = path;
        this.requestHash = requestHash;
        this.expiresAt = expiresAt;
        this.completed = false;
    }

    public void complete(int status, String bodyJson) {
        this.responseStatus = status;
        this.responseBody = bodyJson;
        this.completed = true;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getKey() {
        return key;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public boolean isCompleted() {
        return completed;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
