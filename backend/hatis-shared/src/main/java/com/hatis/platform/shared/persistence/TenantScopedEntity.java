package com.hatis.platform.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

import java.util.UUID;

/**
 * Base type for every tenant-owned entity.
 *
 * <p>The owning organization is assigned once at construction and is immutable:
 * a resource can never be moved to another tenant, which removes an entire class
 * of isolation bugs. Hibernate would reject the change anyway, but making the
 * field write-once means the mistake cannot be expressed.
 */
@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    protected TenantScopedEntity(UUID organizationId) {
        super();
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required for a tenant-scoped entity");
        }
        this.organizationId = organizationId;
    }

    protected TenantScopedEntity(UUID id, UUID organizationId) {
        super(id);
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required for a tenant-scoped entity");
        }
        this.organizationId = organizationId;
    }

    /** JPA requires a no-arg constructor; it is package-private on purpose. */
    protected TenantScopedEntity() {
        super();
    }

    @PrePersist
    void requireTenant() {
        if (organizationId == null) {
            throw new IllegalStateException(
                    "Refusing to persist " + getClass().getSimpleName() + " without an organizationId");
        }
    }

    public UUID getOrganizationId() {
        return organizationId;
    }
}
