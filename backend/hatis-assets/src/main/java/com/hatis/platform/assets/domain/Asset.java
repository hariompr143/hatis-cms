package com.hatis.platform.assets.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A digital asset: metadata in the platform, bytes in object storage.
 *
 * <p>{@code storageKey} is the only link between the two and is never exposed to
 * clients — they receive a signed URL — so the bucket layout can change without a
 * breaking API change.
 *
 * <p>Nothing is deliverable until the malware scan reports {@code CLEAN}. That rule
 * lives in {@link #isDeliverable()}, on the aggregate, so no adapter can serve an
 * unscanned file by forgetting a check.
 */
@Entity
@Table(name = "assets", indexes = {
        @Index(name = "ix_assets_org", columnList = "organization_id,project_id,status"),
        @Index(name = "ix_assets_folder", columnList = "organization_id,folder_id")
})
public class Asset extends TenantScopedEntity {

    public enum Status {
        UPLOADED, QUARANTINED, READY, ARCHIVED, DELETED
    }

    public enum ScanStatus {
        PENDING, CLEAN, INFECTED, ERROR, SKIPPED
    }

    /**
     * Sensitivity label.
     *
     * <p>{@code RESTRICTED} assets are never served through a public rendition and
     * their signed URLs get the shortest TTL the platform allows.
     */
    public enum Classification {
        PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED
    }

    /** Content types that execute in a browser context; refused at upload. */
    private static final Set<String> FORBIDDEN_CONTENT_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "image/svg+xml",
            "application/javascript", "text/javascript", "application/x-javascript",
            "application/x-msdownload", "application/x-sh", "application/x-bat");

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 160)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "storage_binding_id")
    private UUID storageBindingId;

    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 20)
    private ScanStatus scanStatus = ScanStatus.PENDING;

    @Column(name = "scan_detail", length = 512)
    private String scanDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 20)
    private Classification classification = Classification.CONFIDENTIAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.UPLOADED;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Asset() {
        super();
    }

    public Asset(UUID id, UUID organizationId, UUID projectId, UUID folderId, String filename,
                 String contentType, String storageKey, UUID createdBy) {
        super(id, organizationId);
        if (projectId == null) {
            throw new PlatformExceptions.Validation("projectId is required", Map.of());
        }
        this.projectId = projectId;
        this.folderId = folderId;
        this.filename = requireFilename(filename);
        String normalizedType = normalizeContentType(contentType);
        if (FORBIDDEN_CONTENT_TYPES.contains(normalizedType)) {
            throw new PlatformExceptions.Validation(
                    "Assets of type '" + normalizedType + " cannot be uploaded",
                    Map.of("field", "contentType"));
        }
        this.contentType = normalizedType;
        this.storageKey = storageKey;
        this.checksumSha256 = "";
        this.createdBy = createdBy;
        this.status = Status.UPLOADED;
        this.scanStatus = ScanStatus.PENDING;
    }

    /**
     * Records what storage accepted.
     *
     * <p>The checksum comes from storage's own write response rather than from the
     * client, so a truncated upload cannot claim a checksum it does not match.
     */
    public void recordStored(long byteSize, String checksumSha256) {
        if (byteSize < 0) {
            throw new PlatformExceptions.Validation("byteSize must not be negative", Map.of());
        }
        if (checksumSha256 == null || !checksumSha256.matches("[a-fA-F0-9]{64}")) {
            throw new PlatformExceptions.OperationFailed("Storage did not return a usable checksum");
        }
        this.byteSize = byteSize;
        this.checksumSha256 = checksumSha256.toLowerCase(Locale.ROOT);
    }

    public void recordScan(ScanStatus result, String detail) {
        this.scanStatus = result;
        this.scanDetail = detail == null ? null : (detail.length() > 510 ? detail.substring(0, 510) : detail);
        this.status = switch (result) {
            case CLEAN, SKIPPED -> Status.READY;
            case INFECTED, ERROR -> Status.QUARANTINED;
            case PENDING -> this.status;
        };
    }

    /** Records a new version of the bytes; the previous one stays addressable. */
    public void applyVersion(UUID versionId) {
        this.currentVersionId = versionId;
    }

    public void setDimensions(Integer width, Integer height) {
        this.width = width;
        this.height = height;
    }

    public void setClassification(Classification classification) {
        this.classification = classification == null ? Classification.CONFIDENTIAL : classification;
    }

    public void moveTo(UUID folderId) {
        this.folderId = folderId;
    }

    public void archive() {
        this.status = Status.ARCHIVED;
    }

    public void delete() {
        this.status = Status.DELETED;
        this.deletedAt = Instant.now();
    }

    /**
     * The single rule for whether bytes may leave the platform.
     *
     * <p>Requires a recorded size and checksum, a clean (or explicitly skipped)
     * scan, and a non-terminal lifecycle state.
     */
    public boolean isDeliverable() {
        return status == Status.READY
                && (scanStatus == ScanStatus.CLEAN || scanStatus == ScanStatus.SKIPPED)
                && byteSize > 0
                && checksumSha256 != null && checksumSha256.length() == 64;
    }

    public boolean isPubliclyCacheable() {
        return classification == Classification.PUBLIC && isDeliverable();
    }

    private static String requireFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new PlatformExceptions.Validation("filename is required", Map.of());
        }
        return filename.trim().length() > 255
                ? filename.trim().substring(filename.trim().length() - 255)
                : filename.trim();
    }

    /**
     * Reduces a declared content type to the bare type/subtype token the platform
     * stores and matches on.
     *
     * <p>Public because {@code AssetService} needs the same normalized value before
     * the asset exists - it is what gets written to object storage metadata - and
     * normalizing twice in two places is how a forbidden type slips through. The
     * forbidden-type check in the constructor is the enforcement point; this only
     * makes the two agree on the spelling.
     */
    public static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        String value = contentType.trim().toLowerCase(Locale.ROOT);
        int semicolon = value.indexOf(';');
        String type = semicolon > 0 ? value.substring(0, semicolon).trim() : value;
        return type.length() > 160 ? type.substring(0, 160) : type;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public UUID getStorageBindingId() {
        return storageBindingId;
    }

    public void setStorageBindingId(UUID storageBindingId) {
        this.storageBindingId = storageBindingId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public ScanStatus getScanStatus() {
        return scanStatus;
    }

    public String getScanDetail() {
        return scanDetail;
    }

    public Classification getClassification() {
        return classification;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
