package com.hatis.platform.cms.domain;

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
 * A content type: the schema that content items of this kind must satisfy.
 *
 * <p>The schema is the contract between editors, the delivery API and any client
 * application. Changing it is a versioned act ({@code schemaVersion}) so existing
 * content can be interpreted against the schema it was written with.
 */
@Entity
@Table(name = "cms_content_types", indexes = {
        @Index(name = "ix_cms_types_project", columnList = "organization_id,project_id")
})
public class ContentType extends TenantScopedEntity {

    public enum Status {
        DRAFT, ACTIVE, RETIRED
    }

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, length = 63, updatable = false)
    private String slug;

    @Column(name = "description", length = 2000)
    private String description;

    /** JSON Schema (subset) stored as text; parsed and validated on every write. */
    @Column(name = "schema", nullable = false, columnDefinition = "jsonb")
    private String schema;

    @Column(name = "title_field", nullable = false, length = 120)
    private String titleField;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "created_by")
    private UUID createdBy;

    protected ContentType() {
        super();
    }

    public ContentType(UUID organizationId, UUID projectId, String name, String slug,
                       String description, String schema, String titleField, UUID createdBy) {
        super(organizationId);
        this.projectId = require(projectId, "projectId");
        this.name = requireText(name, "name", 120);
        this.slug = requireText(slug, "slug", 63);
        this.description = description;
        this.schema = requireText(schema, "schema", Integer.MAX_VALUE);
        this.titleField = titleField == null || titleField.isBlank() ? "title" : titleField.trim();
        this.status = Status.ACTIVE;
        this.schemaVersion = 1;
        this.createdBy = createdBy;
    }

    /** Publishes a new schema version. Existing content keeps its original version. */
    public void reviseSchema(String newSchema) {
        this.schema = requireText(newSchema, "schema", Integer.MAX_VALUE);
        this.schemaVersion++;
    }

    public void retire() {
        this.status = Status.RETIRED;
    }

    private static UUID require(UUID value, String field) {
        if (value == null) {
            throw new PlatformExceptions.Validation(field + " is required", java.util.Map.of());
        }
        return value;
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new PlatformExceptions.Validation(field + " is required", java.util.Map.of());
        }
        return value.trim();
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

    public String getSchema() {
        return schema;
    }

    public String getTitleField() {
        return titleField;
    }

    public Status getStatus() {
        return status;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
