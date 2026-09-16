package com.hatis.platform.identity.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An API key.
 *
 * <p>Only a SHA-256 hash is stored, so a database leak does not hand out working
 * credentials. The plaintext is shown exactly once, at creation. The 8-character
 * prefix exists purely so a customer can recognise a key in the console and in
 * audit records without being able to use it.
 *
 * <p>Scopes are an upper bound: a key can never exceed the permissions of the
 * service account it belongs to, whatever the request asks for.
 */
@Entity
@Table(name = "idp_api_keys", indexes = {
        @Index(name = "ix_idp_api_keys_hash", columnList = "key_hash", unique = true),
        @Index(name = "ix_idp_api_keys_org", columnList = "organization_id")
})
public class ApiKey extends TenantScopedEntity {

    public static final String PREFIX = "hatis_";

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "key_hash", nullable = false, length = 64, updatable = false)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 16, updatable = false)
    private String keyPrefix;

    @Column(name = "scopes", nullable = false)
    private String scopes;

    @Column(name = "service_account_id", updatable = false)
    private UUID serviceAccountId;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    protected ApiKey() {
        super();
    }

    public ApiKey(UUID organizationId, String name, String keyHash, String keyPrefix,
                  List<String> scopes, UUID serviceAccountId, Instant expiresAt, UUID createdBy) {
        super(organizationId);
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new PlatformExceptions.Validation("name must be 1-120 characters", java.util.Map.of());
        }
        this.name = name.trim();
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.scopes = String.join(",", scopes == null ? List.of() : scopes);
        this.serviceAccountId = serviceAccountId;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void recordUse(Instant now) {
        this.lastUsedAt = now;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public List<String> scopeList() {
        return scopes == null || scopes.isBlank() ? List.of() : List.of(scopes.split(","));
    }

    public boolean hasScope(String scope) {
        return scopeList().contains(scope) || scopeList().contains("*");
    }

    public String getName() {
        return name;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public UUID getServiceAccountId() {
        return serviceAccountId;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
