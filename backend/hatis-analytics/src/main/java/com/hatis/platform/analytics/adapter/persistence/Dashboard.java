package com.hatis.platform.analytics.adapter.persistence;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.Map;
import java.util.UUID;

/**
 * A saved arrangement of charts.
 *
 * <p>Maps {@code anl_dashboards}. The layout is a {@code jsonb} document held as a
 * {@code String} and validated at the service boundary: the console owns what a widget looks
 * like, the platform owns that it is a JSON object and that the tenant may not exceed its plan.
 *
 * <h2>Visibility is not authorization</h2>
 *
 * {@code PROJECT} and {@code ORGANIZATION} are labels a console uses for grouping and
 * discovery. Whether a caller may read a dashboard is decided by {@code analytics:read} at the
 * scope they are acting in — a dashboard marked {@code PRIVATE} is still readable by anyone
 * with the permission in that organization, and treating the column as a security control would
 * make it one that no test could see fail.
 */
@Entity
@Table(name = "anl_dashboards")
public class Dashboard extends TenantScopedEntity {

    public enum Visibility {
        PRIVATE,
        PROJECT,
        ORGANIZATION
    }

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "layout", nullable = false, columnDefinition = "jsonb")
    private String layout;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private Visibility visibility;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected Dashboard() {
        super();
    }

    public Dashboard(UUID organizationId, UUID projectId, String name, String layout,
                     Visibility visibility, UUID createdBy) {
        super(organizationId);
        this.projectId = projectId;
        this.createdBy = createdBy;
        rename(name);
        this.layout = requireLayout(layout);
        this.visibility = visibility == null ? Visibility.PRIVATE : visibility;
    }

    public void rename(String newName) {
        if (newName == null || newName.isBlank() || newName.length() > 200) {
            throw new PlatformExceptions.Validation(
                    "A dashboard name is required and may not exceed 200 characters",
                    Map.of("field", "name"));
        }
        this.name = newName.trim();
    }

    public void changeLayout(String newLayout) {
        this.layout = requireLayout(newLayout);
    }

    public void changeVisibility(Visibility newVisibility) {
        if (newVisibility != null) {
            this.visibility = newVisibility;
        }
    }

    /**
     * The layout must be present and non-empty.
     *
     * <p>Full validation of widget shape belongs to whoever renders it; what belongs here is
     * that the column is {@code not null} and that an empty string is not a layout. Storing
     * {@code ""} in a {@code jsonb} column would fail at the database anyway, and failing here
     * produces a message a customer can act on.
     */
    private static String requireLayout(String value) {
        if (value == null || value.isBlank()) {
            throw new PlatformExceptions.Validation("A dashboard layout is required",
                    Map.of("field", "layout"));
        }
        return value;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getLayout() {
        return layout;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
