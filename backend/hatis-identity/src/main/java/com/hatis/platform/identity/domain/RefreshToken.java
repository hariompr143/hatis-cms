package com.hatis.platform.identity.domain;

import com.hatis.platform.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token, stored only as a SHA-256 hash.
 *
 * <p>Rotation with reuse detection: each use issues a replacement and revokes the
 * presented token, both linked by {@code familyId}. Presenting an already-revoked
 * token from a family means someone replayed a stolen token, so the whole family
 * is revoked. That is the difference between "an attacker has one token" and "an
 * attacker has indefinite access".
 */
@Entity
@Table(name = "idp_refresh_tokens", indexes = {
        @Index(name = "ix_idp_refresh_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "ix_idp_refresh_family", columnList = "family_id")
})
public class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip", length = 64)
    private String ip;

    protected RefreshToken() {
        super();
    }

    public RefreshToken(UUID userId, UUID organizationId, String tokenHash, UUID familyId,
                        Instant expiresAt, String userAgent, String ip) {
        super();
        this.userId = userId;
        this.organizationId = organizationId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ip = ip;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke(UUID replacement) {
        this.revokedAt = Instant.now();
        this.replacedBy = replacement;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIp() {
        return ip;
    }
}
