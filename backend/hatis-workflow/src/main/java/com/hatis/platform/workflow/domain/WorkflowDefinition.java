package com.hatis.platform.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A workflow definition: the states an instance can be in and the transitions between them.
 *
 * <p>Maps {@code wf_definitions}. The JSON document is held as a {@code String} in
 * {@code definition} and parsed by {@code WorkflowDefinitionParser} into a
 * {@link WorkflowDefinitionSpec}, so the domain never depends on Jackson.
 *
 * <h2>Why this entity does not extend {@code BaseEntity}</h2>
 *
 * Two deliberate differences, both visible in the table:
 *
 * <ul>
 *   <li>{@code organization_id} is <strong>nullable</strong>. A row with no organization is
 *       a platform template — the seeded {@code editorial_review} definition — that every
 *       tenant can read and none can change. {@code TenantScopedEntity} refuses to exist
 *       without an organization, and it is right to: a tenant-owned aggregate that cannot
 *       name its tenant is a leak waiting to happen. A catalogue row is a different thing,
 *       and {@code wf_definitions} holds both, so it cannot use that base type.</li>
 *   <li>there is <strong>no optimistic-lock column</strong>. {@code version} on this table
 *       means the definition's own semantic version — 1, 2, 3 — which is why the column
 *       cannot also be BaseEntity's lock. Definitions are superseded by inserting a new
 *       version rather than by editing one in place, and the table is not writable at
 *       runtime at all: {@code V1_013} revokes {@code insert}, {@code update} and
 *       {@code delete} on it from {@code hatis_app}. A lock would guard nothing.</li>
 * </ul>
 */
@Entity
@Table(name = "wf_definitions")
public class WorkflowDefinition {

    public enum Status {
        DRAFT,
        ACTIVE,
        RETIRED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null for a platform template. */
    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    /** Null for a tenant-wide or platform-wide definition. */
    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "key", nullable = false, length = 120, updatable = false)
    private String key;

    @Column(name = "name", nullable = false, length = 200, updatable = false)
    private String name;

    /** The definition's semantic version, not a lock. See the class comment. */
    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
    private String definition;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowDefinition() {
        // JPA.
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public String getDefinition() {
        return definition;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** True when this row belongs to no tenant, so every tenant reads the same copy. */
    public boolean isTemplate() {
        return organizationId == null;
    }

    @Override
    public String toString() {
        return "WorkflowDefinition{key=" + key + ", version=" + version + ", status=" + status
                + ", template=" + isTemplate() + '}';
    }
}
