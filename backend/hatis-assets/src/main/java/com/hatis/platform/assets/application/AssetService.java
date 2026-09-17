package com.hatis.platform.assets.application;

import com.hatis.platform.assets.adapter.persistence.AssetRepositories;
import com.hatis.platform.assets.domain.Asset;
import com.hatis.platform.assets.domain.AssetVersion;
import com.hatis.platform.assets.port.out.MalwareScanner;
import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.id.Identifiers;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.storage.StorageKeys;
import com.hatis.platform.shared.storage.StorageProvider;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Digital asset management.
 *
 * <p>Bytes never pass through the platform process on the way out and are never
 * stored in the database. They are written to object storage under a
 * tenant-scoped key and read back through short-lived signed URLs, so the private
 * bucket is not directly reachable and a leaked URL expires.
 *
 * <p>Upload is synchronous through scanning: the asset is not deliverable until a
 * verdict exists, which closes the window in which a freshly uploaded file could be
 * served unscanned.
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);
    private static final Duration DEFAULT_DOWNLOAD_TTL = Duration.ofMinutes(10);
    private static final Duration RESTRICTED_DOWNLOAD_TTL = Duration.ofMinutes(1);

    private final AssetRepositories.AssetRepository assets;
    private final AssetRepositories.AssetVersionRepository versions;
    private final StorageProvider storage;
    private final MalwareScanner scanner;
    private final AuthorizationService authorization;
    private final QuotaService quotas;
    private final EventPublisher events;

    public AssetService(AssetRepositories.AssetRepository assets,
                        AssetRepositories.AssetVersionRepository versions,
                        StorageProvider storage,
                        MalwareScanner scanner,
                        AuthorizationService authorization,
                        QuotaService quotas,
                        EventPublisher events) {
        this.assets = assets;
        this.versions = versions;
        this.storage = storage;
        this.scanner = scanner;
        this.authorization = authorization;
        this.quotas = quotas;
        this.events = events;
    }

    /** Uploads a new asset, scans it and records the first version. */
    @TenantTransactional
    public AssetSummary upload(UUID projectId, UUID folderId, String filename, String contentType,
                               InputStream content, long contentLength) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("asset:write", AuthorizationService.ScopeType.PROJECT, projectId);
        quotas.check(organizationId, QuotaKey.STORAGE_BYTES, Math.max(contentLength, 0));
        quotas.check(organizationId, QuotaKey.ASSET_COUNT, 1);
        QuotaService.Quota maxAsset = quotas.limit(organizationId, QuotaKey.ASSET_MAX_BYTES);
        if (!maxAsset.unlimited() && contentLength > maxAsset.limit()) {
            throw new PlatformExceptions.QuotaExceeded("asset_max_bytes", maxAsset.limit(), contentLength);
        }

        UUID assetId = Identifiers.newId();
        UUID actor = TenantContextHolder.require().principalId();
        String storageKey = StorageKeys.asset(organizationId, projectId, assetId, 1, filename);
        Asset asset = new Asset(assetId, organizationId, projectId, folderId, filename, contentType,
                storageKey, actor);

        StorageProvider.StoredObject stored = storage.put(
                new StorageProvider.PutRequest(storageKey, asset.getContentType(), null,
                        Map.of("tenant", organizationId.toString(), "project", projectId.toString()),
                        StorageProvider.PutRequest.StorageClass.STANDARD),
                content, contentLength);

        asset.recordStored(stored.byteSize(), stored.checksumSha256());
        applyScan(asset);

        Asset saved = assets.save(asset);
        AssetVersion version = versions.save(new AssetVersion(organizationId, saved.getId(), 1,
                stored.byteSize(), stored.checksumSha256(), storageKey, actor));
        saved.applyVersion(version.getId());
        saved = assets.save(saved);

        quotas.record(organizationId, QuotaKey.STORAGE_BYTES, stored.byteSize());
        quotas.record(organizationId, QuotaKey.ASSET_COUNT, 1);
        events.publish(PlatformEvent.of("asset.uploaded", organizationId)
                .resource("asset", saved.getId())
                .data(Map.of("projectId", projectId.toString(), "byteSize", stored.byteSize(),
                        "contentType", saved.getContentType(), "scanStatus", saved.getScanStatus().name()))
                .build());
        return toSummary(saved);
    }

    /** Uploads a new version of an existing asset. The previous version stays readable. */
    @TenantTransactional
    public AssetSummary uploadVersion(UUID assetId, String filename, String contentType,
                                      InputStream content, long contentLength) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Asset asset = require(assetId, organizationId);
        authorization.require("asset:write", AuthorizationService.ScopeType.PROJECT, asset.getProjectId());
        quotas.check(organizationId, QuotaKey.STORAGE_BYTES, Math.max(contentLength, 0));

        int nextVersion = nextVersionNumber(assetId, organizationId);
        String storageKey = StorageKeys.asset(organizationId, asset.getProjectId(), assetId, nextVersion,
                filename);
        StorageProvider.StoredObject stored = storage.put(
                new StorageProvider.PutRequest(storageKey, Asset.normalizeContentType(contentType), null,
                        Map.of("tenant", organizationId.toString()),
                        StorageProvider.PutRequest.StorageClass.STANDARD),
                content, contentLength);

        // A new version is treated as new, unscanned content until proven otherwise.
        asset.recordStored(stored.byteSize(), stored.checksumSha256());
        applyScan(asset);

        AssetVersion version = versions.save(new AssetVersion(organizationId, assetId, nextVersion,
                stored.byteSize(), stored.checksumSha256(), storageKey,
                TenantContextHolder.require().principalId()));
        asset.applyVersion(version.getId());
        Asset saved = assets.save(asset);

        quotas.record(organizationId, QuotaKey.STORAGE_BYTES, stored.byteSize());
        return toSummary(saved);
    }

    /** Re-runs the scanner; used by the worker when a scan previously errored. */
    @TenantTransactional
    public AssetSummary rescan(UUID assetId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("asset:write", AuthorizationService.ScopeType.ORGANIZATION, organizationId);
        Asset asset = require(assetId, organizationId);
        applyScan(asset);
        return toSummary(assets.save(asset));
    }

    /** Issues a signed download URL for an asset in the authoring console. */
    @TenantTransactional(readOnly = true)
    public DownloadLink downloadUrl(UUID assetId, Duration requestedTtl) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Asset asset = require(assetId, organizationId);
        authorization.require("asset:read", AuthorizationService.ScopeType.PROJECT, asset.getProjectId());
        if (!asset.isDeliverable()) {
            throw new PlatformExceptions.StateConflict(
                    "Asset " + assetId + " is " + asset.getStatus() + " and cannot be downloaded");
        }
        return sign(asset, requestedTtl);
    }

    /**
     * Read path for published sites.
     *
     * <p>Narrower than {@link #downloadUrl} on purpose: no authorization principal
     * is required (an anonymous visitor has none), so the asset must be public and
     * deliverable. Everything else is refused.
     */
    @TenantTransactional(readOnly = true)
    public DownloadLink publicDownloadUrl(UUID assetId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Asset asset = assets.findByIdAndOrganizationId(assetId, organizationId)
                .filter(Asset::isDeliverable)
                .filter(candidate -> candidate.getClassification() == Asset.Classification.PUBLIC)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Asset", assetId));
        return sign(asset, null);
    }

    @TenantTransactional(readOnly = true)
    public AssetSummary get(UUID assetId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Asset asset = require(assetId, organizationId);
        authorization.require("asset:read", AuthorizationService.ScopeType.PROJECT, asset.getProjectId());
        return toSummary(asset);
    }

    @TenantTransactional(readOnly = true)
    public PageResponse<AssetSummary> list(UUID projectId, UUID folderId, int page, int size) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require("asset:read", AuthorizationService.ScopeType.PROJECT, projectId);
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        var pageable = PageResponse.pageable(page, size, sort);
        var result = folderId == null
                ? assets.findByOrganizationIdAndProjectIdAndStatusNot(organizationId, projectId,
                        Asset.Status.DELETED, pageable)
                : assets.findByOrganizationIdAndProjectIdAndFolderIdAndStatusNot(organizationId, projectId,
                        folderId, Asset.Status.DELETED, pageable);
        return PageResponse.from(result, this::toSummary);
    }

    @TenantTransactional
    public AssetSummary setClassification(UUID assetId, Asset.Classification classification) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Asset asset = require(assetId, organizationId);
        authorization.require("asset:write", AuthorizationService.ScopeType.PROJECT, asset.getProjectId());
        asset.setClassification(classification);
        return toSummary(assets.save(asset));
    }

    /**
     * Soft delete.
     *
     * <p>The object is not removed here: a restore inside the retention window must
     * stay possible, and the row must survive for export and audit. A scheduled
     * purge removes objects for rows that have been {@code DELETED} past retention.
     */
    @TenantTransactional
    public void delete(UUID assetId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Asset asset = require(assetId, organizationId);
        authorization.require("asset:write", AuthorizationService.ScopeType.PROJECT, asset.getProjectId());
        asset.delete();
        assets.save(asset);
        quotas.record(organizationId, QuotaKey.STORAGE_BYTES, -asset.getByteSize());
        quotas.record(organizationId, QuotaKey.ASSET_COUNT, -1);
        events.publish(PlatformEvent.of("asset.deleted", organizationId)
                .resource("asset", assetId)
                .build());
    }

    /** Confirms the stored bytes still match the checksum recorded at write time. */
    @TenantTransactional(readOnly = true)
    public boolean verifyIntegrity(UUID assetId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        Asset asset = require(assetId, organizationId);
        String stored = storage.head(asset.getStorageKey()).checksumSha256();
        boolean matches = asset.getChecksumSha256().equalsIgnoreCase(stored);
        if (!matches) {
            log.warn("Checksum mismatch for asset {} key {}", assetId, asset.getStorageKey());
        }
        return matches;
    }

    private void applyScan(Asset asset) {
        MalwareScanner.ScanResult result;
        try {
            result = scanner.scan(asset.getStorageKey());
        } catch (PlatformExceptions.DependencyUnavailable e) {
            // Scanner down is not a pass. Quarantine and let the worker retry.
            asset.recordScan(Asset.ScanStatus.ERROR, "Scanner unavailable");
            return;
        }
        Asset.ScanStatus status = switch (result.verdict()) {
            case CLEAN -> Asset.ScanStatus.CLEAN;
            case INFECTED -> Asset.ScanStatus.INFECTED;
            case ERROR -> Asset.ScanStatus.ERROR;
        };
        asset.recordScan(status, result.detail());
    }

    private DownloadLink sign(Asset asset, Duration requestedTtl) {
        Duration ttl = asset.getClassification() == Asset.Classification.RESTRICTED
                ? RESTRICTED_DOWNLOAD_TTL
                : (requestedTtl == null ? DEFAULT_DOWNLOAD_TTL : requestedTtl);
        StorageProvider.SignedUrl url = storage.presignGet(asset.getStorageKey(), ttl, asset.getFilename());
        return new DownloadLink(asset.getId(), url.url(), url.expiresAt(), asset.getContentType(),
                asset.getByteSize(), asset.getClassification().name());
    }

    private Asset require(UUID assetId, UUID organizationId) {
        return assets.findByIdAndOrganizationId(assetId, organizationId)
                .filter(asset -> asset.getStatus() != Asset.Status.DELETED)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Asset", assetId));
    }

    private int nextVersionNumber(UUID assetId, UUID organizationId) {
        return versions.findMaxVersionNumber(assetId, organizationId) + 1;
    }

    private AssetSummary toSummary(Asset asset) {
        return new AssetSummary(asset.getId(), asset.getProjectId(), asset.getFolderId(),
                asset.getFilename(), asset.getContentType(), asset.getByteSize(),
                asset.getChecksumSha256(), asset.getStatus().name(), asset.getScanStatus().name(),
                asset.getClassification().name(), asset.getWidth(), asset.getHeight(),
                asset.getCreatedAt());
    }

    public record AssetSummary(UUID id, UUID projectId, UUID folderId, String filename, String contentType,
                               long byteSize, String checksumSha256, String status, String scanStatus,
                               String classification, Integer width, Integer height,
                               java.time.Instant createdAt) {
    }

    public record DownloadLink(UUID assetId, String url, java.time.Instant expiresAt, String contentType,
                               long byteSize, String classification) {
    }
}
