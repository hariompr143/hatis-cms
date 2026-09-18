package com.hatis.platform.integration.domain;

import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A connection from this tenant to an external system.
 *
 * <p>Maps to {@code int_integrations}. The credential itself is never stored here:
 * {@link #credentialRef} names a path in the platform secret store, so rotating a
 * webhook secret is a secret-store operation and no credential ever lands in the
 * database, a backup or a log line.
 *
 * <p>The organization is inherited from {@link TenantScopedEntity} and is write-once,
 * which is what makes an inbound webhook safe: the tenant is resolved from this row
 * after the signature verifies, never from anything the caller sent.
 */
@Entity
@Table(name = "int_integrations")
public class InboundIntegration extends TenantScopedEntity {

    /** Systems the platform can exchange events with. */
    public enum Type {
        GITHUB,
        GITLAB,
        BITBUCKET,
        AWS,
        AZURE,
        GCP,
        SLACK,
        GENERIC_WEBHOOK
    }

    /** Lifecycle of the connection. A webhook is only accepted while CONNECTED. */
    public enum Status {
        PENDING,
        CONNECTED,
        ERROR,
        DISCONNECTED
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private Type type;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Non-secret provider settings. Must never hold a credential or token. */
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private String config;

    /** Secret-store path holding the webhook signing secret. Never the secret itself. */
    @Column(name = "credential_ref", length = 512)
    private String credentialRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected InboundIntegration() {
        super();
    }

    public InboundIntegration(UUID organizationId, Type type, String name, String config,
                              String credentialRef) {
        super(organizationId);
        if (type == null) {
            throw new IllegalArgumentException("integration type is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("integration name is required");
        }
        this.type = type;
        this.name = name;
        this.config = config == null ? "{}" : config;
        this.credentialRef = credentialRef;
        this.status = Status.PENDING;
    }

    /** Marks the connection usable. Only a CONNECTED integration accepts deliveries. */
    public void connect() {
        this.status = Status.CONNECTED;
    }

    /**
     * Points this integration at a signing secret in the secret store.
     *
     * <p>Takes a path and never the secret itself: the value lives in the secret
     * manager, so rotating it does not touch this row and no credential reaches the
     * database, a backup or a log line.
     */
    public void bindCredential(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalArgumentException("a credential reference is required to connect an integration");
        }
        this.credentialRef = credentialRef;
    }

    /** Clears the credential and stops deliveries. The stored secret is deleted separately. */
    public void disconnect() {
        this.status = Status.DISCONNECTED;
        this.credentialRef = null;
    }

    /** Records that a verified delivery arrived, so operators can see a dead webhook. */
    public void recordUsed(Instant at) {
        this.lastUsedAt = at;
    }

    public boolean acceptsDeliveries() {
        return status == Status.CONNECTED && credentialRef != null && !credentialRef.isBlank();
    }

    public Type getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getConfig() {
        return config;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
