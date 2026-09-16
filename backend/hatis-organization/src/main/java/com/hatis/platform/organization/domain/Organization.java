package com.hatis.platform.organization.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.id.Identifiers;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The tenant.
 *
 * <p>An organization owns every tenant-scoped resource and is the unit of
 * isolation, billing and entitlements. Its {@code organization_id} column is its
 * own id, which lets one row level security policy shape cover every tenant table
 * without a special case for this one.
 */
@Entity
@Table(name = "org_organizations")
public class Organization extends TenantScopedEntity {

    public enum Status {
        PENDING,
        ACTIVE,
        SUSPENDED,
        CLOSED
    }

    /** How strongly this tenant's data is separated. See architecture doc 06. */
    public enum IsolationMode {
        SHARED_SCHEMA,
        DEDICATED_SCHEMA,
        DEDICATED_DATABASE,
        CUSTOMER_MANAGED
    }

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "slug", nullable = false, length = 63, unique = true, updatable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "plan_code", nullable = false, length = 64)
    private String planCode;

    @Column(name = "region", length = 32)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(name = "isolation_mode", nullable = false, length = 32)
    private IsolationMode isolationMode;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Organization() {
        super();
    }

    public Organization(String name, String slug, String planCode, String region) {
        this(Identifiers.newId(), name, slug, planCode, region);
    }

    public Organization(UUID id, String name, String slug, String planCode, String region) {
        // The tenant root references itself, so the two identifiers are identical.
        super(id, id);
        this.name = requireName(name);
        this.slug = requireSlug(slug);
        this.planCode = planCode == null || planCode.isBlank() ? "starter" : planCode;
        this.region = region;
        this.status = Status.ACTIVE;
        this.isolationMode = IsolationMode.SHARED_SCHEMA;
    }

    public void rename(String newName) {
        this.name = requireName(newName);
    }

    public void changePlan(String newPlanCode) {
        if (newPlanCode == null || newPlanCode.isBlank()) {
            throw new PlatformExceptions.Validation("planCode is required", java.util.Map.of());
        }
        this.planCode = newPlanCode;
    }

    public void setIsolationMode(IsolationMode mode) {
        this.isolationMode = mode == null ? IsolationMode.SHARED_SCHEMA : mode;
    }

    public void suspend(String reason) {
        if (status == Status.CLOSED) {
            throw new PlatformExceptions.StateConflict("A closed organization cannot be suspended");
        }
        this.status = Status.SUSPENDED;
    }

    public void activate() {
        if (status == Status.CLOSED) {
            throw new PlatformExceptions.StateConflict("A closed organization cannot be reactivated");
        }
        this.status = Status.ACTIVE;
    }

    /**
     * Closes the organization. Data is retained for the contractual retention
     * window and is exportable until then; it is not deleted here.
     */
    public void close() {
        this.status = Status.CLOSED;
        this.deletedAt = Instant.now();
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public void requireActive() {
        if (!isActive()) {
            throw new PlatformExceptions.Forbidden("The organization is " + status.name().toLowerCase());
        }
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new PlatformExceptions.Validation("name must be 1-200 characters", java.util.Map.of());
        }
        return value.trim();
    }

    private static String requireSlug(String value) {
        if (!Identifiers.isValidSlug(value)) {
            throw new PlatformExceptions.Validation(
                    "slug must be 1-63 characters of lowercase letters, digits and hyphens",
                    java.util.Map.of());
        }
        return value;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Status getStatus() {
        return status;
    }

    public String getPlanCode() {
        return planCode;
    }

    public String getRegion() {
        return region;
    }

    public IsolationMode getIsolationMode() {
        return isolationMode;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
