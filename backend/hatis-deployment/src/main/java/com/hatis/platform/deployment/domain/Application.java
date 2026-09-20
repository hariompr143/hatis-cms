package com.hatis.platform.deployment.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A deployable application inside a project.
 *
 * <p>Holds the shape of the workload — namespace, resources, health endpoints — so
 * that a deployment is a release choice rather than a fresh set of infrastructure
 * decisions every time.
 */
@Entity
@Table(name = "dep_applications", indexes = {
        @Index(name = "ix_dep_apps_project", columnList = "organization_id,project_id")
})
public class Application extends TenantScopedEntity {

    public enum Runtime {
        CONTAINER, STATIC, FUNCTION
    }

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, length = 63, updatable = false)
    private String slug;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "runtime", nullable = false, length = 20)
    private Runtime runtime = Runtime.CONTAINER;

    @Column(name = "default_port", nullable = false)
    private int defaultPort = 8080;

    @Column(name = "cpu_request", length = 16)
    private String cpuRequest = "100m";

    @Column(name = "cpu_limit", length = 16)
    private String cpuLimit = "1000m";

    @Column(name = "memory_request", length = 16)
    private String memoryRequest = "128Mi";

    @Column(name = "memory_limit", length = 16)
    private String memoryLimit = "512Mi";

    @Column(name = "health_path", length = 255)
    private String healthPath = "/internal/health/liveness";

    @Column(name = "readiness_path", length = 255)
    private String readinessPath = "/internal/health/readiness";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Application() {
        super();
    }

    public Application(UUID organizationId, UUID projectId, String name, String slug, String description,
                       UUID createdBy) {
        super(organizationId);
        if (projectId == null) {
            throw new PlatformExceptions.Validation("projectId is required", Map.of());
        }
        if (slug == null || !slug.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new PlatformExceptions.Validation(
                    "slug must start with a lowercase letter and contain only lowercase letters, digits"
                            + " and hyphens (max 63 characters)",
                    Map.of("field", "slug"));
        }
        this.projectId = projectId;
        this.name = name == null || name.isBlank() ? slug : name.trim();
        this.slug = slug;
        this.description = description;
        this.createdBy = createdBy;
    }

    public void resize(String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit) {
        this.cpuRequest = cpuRequest;
        this.cpuLimit = cpuLimit;
        this.memoryRequest = memoryRequest;
        this.memoryLimit = memoryLimit;
    }

    public void archive() {
        this.archivedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
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

    public String getDescription() {
        return description;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public String getCpuRequest() {
        return cpuRequest;
    }

    public String getCpuLimit() {
        return cpuLimit;
    }

    public String getMemoryRequest() {
        return memoryRequest;
    }

    public String getMemoryLimit() {
        return memoryLimit;
    }

    public String getHealthPath() {
        return healthPath;
    }

    public String getReadinessPath() {
        return readinessPath;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
