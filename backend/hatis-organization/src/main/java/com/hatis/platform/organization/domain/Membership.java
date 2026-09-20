package com.hatis.platform.organization.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's membership of an organization, with the role that grants default
 * permissions across the whole tenant.
 *
 * <p>Narrower permissions come from role bindings in the authorization context;
 * this row answers "is this user in this tenant at all".
 */
@Entity
@Table(name = "org_memberships", indexes = {
        @Index(name = "ix_org_memberships_user", columnList = "organization_id,user_id", unique = true)
})
public class Membership extends TenantScopedEntity {

    public enum Status {
        INVITED,
        ACTIVE,
        SUSPENDED,
        REMOVED
    }

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "role", nullable = false, length = 64)
    private String role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "joined_at")
    private Instant joinedAt;

    protected Membership() {
        super();
    }

    public Membership(UUID organizationId, UUID userId, String role, UUID invitedBy) {
        super(organizationId);
        if (userId == null) {
            throw new PlatformExceptions.Validation("userId is required", java.util.Map.of());
        }
        this.userId = userId;
        this.role = role == null || role.isBlank() ? "VIEWER" : role;
        this.invitedBy = invitedBy;
        this.invitedAt = Instant.now();
        this.status = invitedBy == null ? Status.ACTIVE : Status.INVITED;
        if (this.status == Status.ACTIVE) {
            this.joinedAt = this.invitedAt;
        }
    }

    public void accept() {
        if (status == Status.REMOVED) {
            throw new PlatformExceptions.StateConflict("A removed membership cannot be accepted");
        }
        this.status = Status.ACTIVE;
        this.joinedAt = Instant.now();
    }

    public void changeRole(String newRole) {
        if (newRole == null || newRole.isBlank()) {
            throw new PlatformExceptions.Validation("role is required", java.util.Map.of());
        }
        this.role = newRole;
    }

    public void suspend() {
        this.status = Status.SUSPENDED;
    }

    public void remove() {
        this.status = Status.REMOVED;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
