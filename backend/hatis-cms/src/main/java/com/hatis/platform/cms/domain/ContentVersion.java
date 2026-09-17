package com.hatis.platform.cms.domain;

import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * An immutable snapshot of a content item's body.
 *
 * <p>Nothing in the platform ever updates a version row. Editing creates the next
 * version number. This is the property that makes rollback, preview, audit and
 * scheduled publishing all straightforward rather than special cases.
 */
@Entity
@Table(name = "cms_content_versions", indexes = {
        @Index(name = "ix_cms_versions_item", columnList = "content_item_id,version_number")
})
public class ContentVersion extends TenantScopedEntity {

    @Column(name = "content_item_id", nullable = false, updatable = false)
    private UUID contentItemId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "body", nullable = false, columnDefinition = "jsonb")
    private String body;

    /**
     * Flattened, tag-stripped text used for search.
     *
     * <p>Derived data: it can always be rebuilt from {@code body}, so it is never
     * treated as a source of truth.
     */
    @Column(name = "search_text")
    private String searchText;

    @Column(name = "change_note", length = 512)
    private String changeNote;

    @Column(name = "created_by")
    private UUID createdBy;

    protected ContentVersion() {
        super();
    }

    public ContentVersion(UUID organizationId, UUID contentItemId, int versionNumber,
                          String body, String searchText, String changeNote, UUID createdBy) {
        super(organizationId);
        this.contentItemId = contentItemId;
        this.versionNumber = versionNumber;
        this.body = body;
        this.searchText = searchText;
        this.changeNote = changeNote;
        this.createdBy = createdBy;
    }

    public UUID getContentItemId() {
        return contentItemId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getBody() {
        return body;
    }

    public String getSearchText() {
        return searchText;
    }

    public String getChangeNote() {
        return changeNote;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
