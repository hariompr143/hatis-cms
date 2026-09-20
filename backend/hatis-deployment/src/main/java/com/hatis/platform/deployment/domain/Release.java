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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A release: a specific, immutable build that can be deployed.
 *
 * <p>Releases are separate from deployments so that "the thing that is running" and
 * "the things we could run" are different lists. Rollback is then a matter of
 * pointing an environment at an earlier release, not of rebuilding anything.
 *
 * <p>{@code digest} pins the image by content hash. A tag can be repushed; a digest
 * cannot, so a rollback genuinely restores the same bytes.
 */
@Entity
@Table(name = "dep_releases", indexes = {
        @Index(name = "ix_dep_releases_app", columnList = "organization_id,application_id,status")
})
public class Release extends TenantScopedEntity {

    public enum SourceType {
        REGISTRY, GITHUB, GITLAB, BITBUCKET, UPLOAD, EXTERNAL_PIPELINE
    }

    public enum ScanStatus {
        PENDING, PASSED, FAILED, SKIPPED
    }

    public enum Status {
        CREATED, READY, REJECTED, SUPERSEDED
    }

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "release_version", nullable = false, length = 120, updatable = false)
    private String releaseVersion;

    @Column(name = "image", nullable = false, length = 512)
    private String image;

    @Column(name = "digest", length = 128)
    private String digest;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "source_ref", length = 512)
    private String sourceRef;

    @Column(name = "git_commit", length = 64)
    private String gitCommit;

    @Column(name = "sbom_ref", length = 512)
    private String sbomRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 20)
    private ScanStatus scanStatus;

    @Column(name = "scan_summary", length = 1000)
    private String scanSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "created_by")
    private UUID createdBy;

    protected Release() {
        super();
    }

    public Release(UUID organizationId, UUID applicationId, String version, String image, String digest,
                   SourceType sourceType, String sourceRef, String gitCommit, UUID createdBy) {
        super(organizationId);
        if (applicationId == null) {
            throw new PlatformExceptions.Validation("applicationId is required", Map.of());
        }
        if (version == null || version.isBlank()) {
            throw new PlatformExceptions.Validation("version is required", Map.of());
        }
        // A floating tag such as "latest" makes rollback and audit meaningless: the
        // name stays the same while the content changes underneath it.
        String normalizedImage = image == null ? "" : image.trim();
        if (normalizedImage.isEmpty() || normalizedImage.endsWith(":latest")
                || !normalizedImage.contains(":") && digest == null) {
            throw new PlatformExceptions.Validation(
                    "An image must be pinned to a tag or a digest; 'latest' is not deployable",
                    Map.of("field", "image"));
        }
        this.applicationId = applicationId;
        this.releaseVersion = version.trim().toLowerCase(Locale.ROOT);
        this.image = normalizedImage;
        this.digest = digest;
        this.sourceType = sourceType == null ? SourceType.REGISTRY : sourceType;
        this.sourceRef = sourceRef;
        this.gitCommit = gitCommit;
        this.scanStatus = ScanStatus.PENDING;
        this.status = Status.CREATED;
        this.createdBy = createdBy;
    }

    /**
     * Records scanner output.
     *
     * <p>A failed scan leaves the release un-deployable: {@code READY} is the only
     * state {@link Deployment} will accept. Enforcement lives in the deploy path,
     * not in the UI, so an API client cannot bypass it.
     */
    public void recordScan(ScanStatus result, String summary) {
        this.scanStatus = result;
        this.scanSummary = summary == null ? null
                : (summary.length() > 990 ? summary.substring(0, 990) : summary);
        this.status = result == ScanStatus.PASSED || result == ScanStatus.SKIPPED
                ? Status.READY
                : Status.REJECTED;
    }

    /** A deployment may only start from a release that passed scanning. */
    public void requireDeployable() {
        if (status != Status.READY) {
            throw new PlatformExceptions.StateConflict(
                    "Release " + releaseVersion + " is " + status + " and cannot be deployed");
        }
    }

    public void supersede() {
        this.status = Status.SUPERSEDED;
    }

    public boolean isDeployable() {
        return status == Status.READY;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    /** The release's own version, distinct from the entity's optimistic-lock version. */
    public String getReleaseVersion() {
        return releaseVersion;
    }

    public String getImage() {
        return image;
    }

    public String getDigest() {
        return digest;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getGitCommit() {
        return gitCommit;
    }

    public String getSbomRef() {
        return sbomRef;
    }

    public void setSbomRef(String sbomRef) {
        this.sbomRef = sbomRef;
    }

    public ScanStatus getScanStatus() {
        return scanStatus;
    }

    public String getScanSummary() {
        return scanSummary;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
