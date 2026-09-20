package com.hatis.platform.organization.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.id.Identifiers;
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
 * A unit of work inside an organization: a website, a portal, an application.
 *
 * <p>Projects are the scope most permissions are bound to, and the boundary used
 * for content, assets and quotas.
 */
@Entity
@Table(name = "org_projects", indexes = {
        @Index(name = "ix_org_projects_org", columnList = "organization_id"),
        @Index(name = "uq_org_projects_org_slug", columnList = "organization_id,slug", unique = true)
})
public class Project extends TenantScopedEntity {

    public enum Status {
        ACTIVE,
        ARCHIVED,
        DELETED
    }

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "slug", nullable = false, length = 63)
    private String slug;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Project() {
        super();
    }

    public Project(UUID organizationId, String name, String description) {
        super(organizationId);
        this.name = require(name, "name", 200);
        String derived = Identifiers.slugify(name);
        if (derived == null) {
            throw new PlatformExceptions.Validation("name must contain at least one letter or digit",
                    java.util.Map.of());
        }
        this.slug = derived;
        this.description = description;
        this.status = Status.ACTIVE;
    }

    public Project(UUID organizationId, String name, String slug, String description) {
        super(organizationId);
        this.name = require(name, "name", 200);
        this.slug = require(slug, "slug", 63);
        this.description = description;
        this.status = Status.ACTIVE;
    }

    public void update(String newName, String newDescription) {
        this.name = require(newName, "name", 200);
        this.description = newDescription;
    }

    public void archive() {
        if (status == Status.DELETED) {
            throw new PlatformExceptions.StateConflict("A deleted project cannot be archived");
        }
        this.status = Status.ARCHIVED;
    }

    /** Soft delete: a customer can undo this, and exports must still see the data. */
    public void delete() {
        this.status = Status.DELETED;
        this.deletedAt = Instant.now();
    }

    public void requireUsable() {
        if (status != Status.ACTIVE) {
            throw new PlatformExceptions.StateConflict("Project is " + status.name().toLowerCase());
        }
    }

    private static String require(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new PlatformExceptions.Validation(field + " must be 1-" + max + " characters",
                    java.util.Map.of());
        }
        return value.trim();
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
