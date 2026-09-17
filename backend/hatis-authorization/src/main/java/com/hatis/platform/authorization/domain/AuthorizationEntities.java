package com.hatis.platform.authorization.domain;

import com.hatis.platform.shared.persistence.BaseEntity;
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
 * Authorization aggregates.
 *
 * <p>Permissions are data rather than an enum so that adding a capability is a
 * reviewed migration instead of a code change that must be deployed everywhere at
 * once. The evaluation rules themselves live in {@code AuthorizationService}.
 */
public final class AuthorizationEntities {

    private AuthorizationEntities() {
    }

    @Entity
    @Table(name = "auth_permissions")
    public static class Permission extends BaseEntity {

        @Column(name = "code", nullable = false, length = 120, unique = true, updatable = false)
        private String code;

        @Column(name = "description", length = 512)
        private String description;

        @Column(name = "resource_type", nullable = false, length = 64)
        private String resourceType;

        @Column(name = "action", nullable = false, length = 64)
        private String action;

        @Column(name = "classification", nullable = false, length = 20)
        private String classification;

        protected Permission() {
            super();
        }

        public String getCode() {
            return code;
        }

        public String getResourceType() {
            return resourceType;
        }

        public String getAction() {
            return action;
        }

        public String getClassification() {
            return classification;
        }
    }

    @Entity
    @Table(name = "auth_roles", indexes = {
            @Index(name = "ix_auth_roles_org", columnList = "organization_id")
    })
    public static class Role extends BaseEntity {

        /** Null marks a system role shipped with the platform. */
        @Column(name = "organization_id")
        private UUID organizationId;

        @Column(name = "code", nullable = false, length = 64)
        private String code;

        @Column(name = "name", nullable = false, length = 120)
        private String name;

        @Column(name = "description", length = 512)
        private String description;

        @Column(name = "system", nullable = false)
        private boolean system;

        protected Role() {
            super();
        }

        public UUID getOrganizationId() {
            return organizationId;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public boolean isSystem() {
            return system;
        }
    }

    /**
     * Grants a role to a principal at a scope.
     *
     * <p>{@code effect = DENY} at a narrow scope overrides an allow inherited from
     * a wider one, which is what makes "this developer may deploy everywhere
     * except production" expressible without inventing a new role.
     */
    @Entity
    @Table(name = "auth_role_bindings", indexes = {
            @Index(name = "ix_auth_bindings_lookup_idx", columnList = "organization_id,principal_type,principal_id")
    })
    public static class RoleBinding extends TenantScopedEntity {

        public enum ScopeType {
            ORGANIZATION, WORKSPACE, PROJECT, ENVIRONMENT, RESOURCE
        }

        public enum Effect {
            ALLOW, DENY
        }

        @Column(name = "role_id", nullable = false, updatable = false)
        private UUID roleId;

        @Column(name = "principal_type", nullable = false, length = 20, updatable = false)
        private String principalType;

        @Column(name = "principal_id", nullable = false, updatable = false)
        private UUID principalId;

        @Enumerated(EnumType.STRING)
        @Column(name = "scope_type", nullable = false, length = 20, updatable = false)
        private ScopeType scopeType;

        @Column(name = "scope_id", updatable = false)
        private UUID scopeId;

        @Enumerated(EnumType.STRING)
        @Column(name = "effect", nullable = false, length = 10)
        private Effect effect = Effect.ALLOW;

        @Column(name = "expires_at")
        private Instant expiresAt;

        @Column(name = "created_by")
        private UUID createdBy;

        protected RoleBinding() {
            super();
        }

        public RoleBinding(UUID organizationId, UUID roleId, String principalType, UUID principalId,
                           ScopeType scopeType, UUID scopeId, Effect effect, UUID createdBy) {
            super(organizationId);
            this.roleId = roleId;
            this.principalType = principalType;
            this.principalId = principalId;
            this.scopeType = scopeType;
            this.scopeId = scopeId;
            this.effect = effect == null ? Effect.ALLOW : effect;
            this.createdBy = createdBy;
        }

        public boolean isActive(Instant now) {
            return expiresAt == null || expiresAt.isAfter(now);
        }

        public UUID getRoleId() {
            return roleId;
        }

        public String getPrincipalType() {
            return principalType;
        }

        public UUID getPrincipalId() {
            return principalId;
        }

        public ScopeType getScopeType() {
            return scopeType;
        }

        public UUID getScopeId() {
            return scopeId;
        }

        public Effect getEffect() {
            return effect;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        public UUID getCreatedBy() {
            return createdBy;
        }
    }
}
