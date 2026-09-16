package com.hatis.platform.organization.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A deployment target inside a project.
 *
 * <p>Each environment has its own configuration, secrets, database, storage,
 * domains and deployments. Production is a distinct kind, not just a name: it
 * carries a stronger authorization requirement and cannot be deleted while it
 * holds an active deployment.
 */
@Entity
@Table(name = "org_environments", indexes = {
        @Index(name = "ix_org_environments_project", columnList = "organization_id,project_id")
})
public class Environment extends TenantScopedEntity {

    public enum Kind {
        DEVELOPMENT,
        PREVIEW,
        QA,
        UAT,
        STAGING,
        PRODUCTION,
        CUSTOM
    }

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "slug", nullable = false, length = 63)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind;

    @Column(name = "production", nullable = false)
    private boolean production;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    protected Environment() {
        super();
    }

    public Environment(UUID organizationId, UUID projectId, String name, String slug, Kind kind, int orderIndex) {
        super(organizationId);
        if (projectId == null) {
            throw new PlatformExceptions.Validation("projectId is required", java.util.Map.of());
        }
        this.projectId = projectId;
        this.name = name == null || name.isBlank() ? kind.name() : name.trim();
        this.slug = slug == null || slug.isBlank() ? kind.name().toLowerCase(java.util.Locale.ROOT) : slug.trim();
        this.kind = kind;
        this.production = kind == Kind.PRODUCTION;
        this.orderIndex = orderIndex;
    }

    public boolean isProduction() {
        return production;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Kind getKind() {
        return kind;
    }

    public int getOrderIndex() {
        return orderIndex;
    }
}
