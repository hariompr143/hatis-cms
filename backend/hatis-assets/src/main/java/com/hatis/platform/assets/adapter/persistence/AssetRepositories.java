package com.hatis.platform.assets.adapter.persistence;

import com.hatis.platform.assets.domain.Asset;
import com.hatis.platform.assets.domain.AssetVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Asset persistence.
 *
 * <p>Every finder takes {@code organizationId} explicitly. Spring Data will not add
 * a tenant predicate for us, and row level security is the backstop rather than the
 * primary control.
 */
public interface AssetRepositories {

    interface AssetRepository extends JpaRepository<Asset, UUID> {

        Optional<Asset> findByIdAndOrganizationId(UUID id, UUID organizationId);

        Page<Asset> findByOrganizationIdAndProjectIdAndStatusNot(UUID organizationId,
                                                                 UUID projectId,
                                                                 Asset.Status status,
                                                                 Pageable pageable);

        Page<Asset> findByOrganizationIdAndProjectIdAndFolderIdAndStatusNot(UUID organizationId,
                                                                            UUID projectId,
                                                                            UUID folderId,
                                                                            Asset.Status status,
                                                                            Pageable pageable);

        List<Asset> findByOrganizationIdAndScanStatus(UUID organizationId, Asset.ScanStatus scanStatus);

        /** Assets soft-deleted before the cutoff; consumed by the purge job. */
        @Query("""
                select a from Asset a
                where a.status = com.hatis.platform.assets.domain.Asset$Status.DELETED
                  and a.deletedAt < :cutoff
                """)
        List<Asset> findPurgeable(@Param("cutoff") java.time.Instant cutoff, Pageable pageable);
    }

    interface AssetVersionRepository extends JpaRepository<AssetVersion, UUID> {

        Optional<AssetVersion> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<AssetVersion> findByAssetIdAndOrganizationIdOrderByVersionNumberDesc(UUID assetId,
                                                                                  UUID organizationId);

        @Query("""
                select coalesce(max(v.versionNumber), 0) from AssetVersion v
                where v.assetId = :assetId and v.organizationId = :organizationId
                """)
        int findMaxVersionNumber(@Param("assetId") UUID assetId,
                                 @Param("organizationId") UUID organizationId);
    }
}
