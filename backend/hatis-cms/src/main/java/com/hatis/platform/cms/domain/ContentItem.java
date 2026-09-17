package com.hatis.platform.cms.domain;

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
 * A content item: a mutable pointer to an immutable version.
 *
 * <p>Publishing sets {@code publishedVersionId}; editing never touches it. That
 * separation is what makes rollback honest — the previously published version is
 * still exactly what it was — and what makes preview safe, because a draft can be
 * shown without ever being reachable through the delivery API.
 */
@Entity
@Table(name = "cms_content_items", indexes = {
        @Index(name = "ix_cms_items_project", columnList = "organization_id,project_id,status"),
        @Index(name = "ix_cms_items_type", columnList = "organization_id,content_type_id")
})
public class ContentItem extends TenantScopedEntity {

    public enum Status {
        DRAFT, IN_REVIEW, APPROVED, PUBLISHED, ARCHIVED, DELETED
    }

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "content_type_id", nullable = false, updatable = false)
    private UUID contentTypeId;

    @Column(name = "slug", nullable = false, length = 255)
    private String slug;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "published_version_id")
    private UUID publishedVersionId;

    @Column(name = "workflow_instance_id")
    private UUID workflowInstanceId;

    @Column(name = "source_item_id")
    private UUID sourceItemId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ContentItem() {
        super();
    }

    public ContentItem(UUID organizationId, UUID projectId, UUID contentTypeId, String slug,
                       String locale, UUID createdBy) {
        super(organizationId);
        if (projectId == null || contentTypeId == null) {
            throw new PlatformExceptions.Validation("projectId and contentTypeId are required",
                    java.util.Map.of());
        }
        this.projectId = projectId;
        this.contentTypeId = contentTypeId;
        this.slug = normalizeSlug(slug);
        this.locale = locale == null || locale.isBlank() ? "en" : locale.trim();
        this.status = Status.DRAFT;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    /** Records a new draft version. Does not affect what is published. */
    public void applyDraft(UUID versionId, UUID updatedBy) {
        this.currentVersionId = versionId;
        this.updatedBy = updatedBy;
    }

    public void submitForReview(UUID workflowInstanceId) {
        if (status != Status.DRAFT && status != Status.APPROVED) {
            throw new PlatformExceptions.StateConflict("Cannot submit content in state " + status);
        }
        this.workflowInstanceId = workflowInstanceId;
        this.status = Status.IN_REVIEW;
    }

    /** Review rejected it: back to draft so the author can act on the comments. */
    public void reject() {
        if (status != Status.IN_REVIEW) {
            throw new PlatformExceptions.StateConflict("Only content in review can be rejected");
        }
        this.status = Status.DRAFT;
    }

    public void approve() {
        if (status != Status.IN_REVIEW) {
            throw new PlatformExceptions.StateConflict("Only content in review can be approved");
        }
        this.status = Status.APPROVED;
    }

    /** Publishes the current version. Requires a version and an approvable state. */
    public void publish() {
        if (currentVersionId == null) {
            throw new PlatformExceptions.StateConflict("There is no content version to publish");
        }
        if (status == Status.ARCHIVED || status == Status.DELETED) {
            throw new PlatformExceptions.StateConflict("Content in state " + status + " cannot be published");
        }
        this.publishedVersionId = currentVersionId;
        this.status = Status.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    /**
     * Unpublishes without losing the draft. The item returns to DRAFT so an editor
     * can continue working; the published version pointer is cleared so the
     * delivery API stops serving it.
     */
    public void unpublish() {
        if (status != Status.PUBLISHED) {
            throw new PlatformExceptions.StateConflict("Only published content can be unpublished");
        }
        this.publishedVersionId = null;
        this.publishedAt = null;
        this.status = Status.DRAFT;
    }

    /**
     * Rolls back to a specific earlier version.
     *
     * <p>This publishes that version without deleting anything, so the full history
     * stays auditable.
     */
    public void rollbackTo(UUID versionId) {
        if (versionId == null) {
            throw new PlatformExceptions.Validation("versionId is required", java.util.Map.of());
        }
        this.publishedVersionId = versionId;
        this.currentVersionId = versionId;
        this.status = Status.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public void archive() {
        this.status = Status.ARCHIVED;
    }

    /** Soft delete: exports must still see the content during the retention window. */
    public void delete() {
        this.status = Status.DELETED;
        this.deletedAt = Instant.now();
    }

    public boolean isPublished() {
        return status == Status.PUBLISHED && publishedVersionId != null;
    }

    static String normalizeSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new PlatformExceptions.Validation("slug is required", java.util.Map.of());
        }
        String normalized = slug.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9/_-]", "");
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new PlatformExceptions.Validation(
                    "slug must be 1-255 characters of lowercase letters, digits, hyphens, underscores and slashes",
                    java.util.Map.of());
        }
        return normalized;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getContentTypeId() {
        return contentTypeId;
    }

    public String getSlug() {
        return slug;
    }

    public String getLocale() {
        return locale;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public UUID getPublishedVersionId() {
        return publishedVersionId;
    }

    public UUID getWorkflowInstanceId() {
        return workflowInstanceId;
    }

    public UUID getSourceItemId() {
        return sourceItemId;
    }

    public void setSourceItemId(UUID sourceItemId) {
        this.sourceItemId = sourceItemId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
