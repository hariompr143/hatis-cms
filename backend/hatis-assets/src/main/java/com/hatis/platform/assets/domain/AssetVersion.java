package com.hatis.platform.assets.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Map;
import java.util.UUID;

/**
 * An immutable version of an asset's bytes.
 *
 * <p>Replacing an asset never overwrites the previous object: a page that already
 * references it keeps resolving, and a customer can restore the earlier rendition.
 */
@Entity
@Table(name = "asset_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_asset_versions", columnNames = {"asset_id", "version_number"})
})
public class AssetVersion extends TenantScopedEntity {

    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "storage_key", nullable = false, length = 1024, updatable = false)
    private String storageKey;

    @Column(name = "created_by")
    private UUID createdBy;

    protected AssetVersion() {
        super();
    }

    public AssetVersion(UUID organizationId, UUID assetId, int versionNumber, long byteSize,
                        String checksumSha256, String storageKey, UUID createdBy) {
        super(organizationId);
        if (assetId == null || storageKey == null || storageKey.isBlank()) {
            throw new PlatformExceptions.Validation("assetId and storageKey are required", Map.of());
        }
        if (byteSize < 0) {
            throw new PlatformExceptions.Validation("byteSize must not be negative", Map.of());
        }
        this.assetId = assetId;
        this.versionNumber = versionNumber;
        this.byteSize = byteSize;
        this.checksumSha256 = checksumSha256 == null ? "" : checksumSha256.toLowerCase();
        this.storageKey = storageKey;
        this.createdBy = createdBy;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public long getByteSize() {
        return byteSize;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
