package com.hatis.platform.assets.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asset invariants.
 *
 * <p>The rule under test is {@link Asset#isDeliverable()}: bytes must not leave the
 * platform unless they have been stored, checksummed and scanned. A regression here
 * is a data-exposure or malware-serving bug, not a cosmetic one.
 */
class AssetTest {

    private static final String SHA = "a".repeat(64);

    private Asset newAsset() {
        return new Asset(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "report.pdf", "application/pdf", "org/x/asset/1/report.pdf", UUID.randomUUID());
    }

    private Asset storedAndScanned() {
        Asset asset = newAsset();
        asset.recordStored(2048L, SHA);
        asset.recordScan(Asset.ScanStatus.CLEAN, null);
        return asset;
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xhtml+xml", "image/svg+xml",
            "application/javascript", "text/javascript", "application/x-sh"})
    @DisplayName("content types that execute in a browser are refused at upload")
    void refusesExecutableContentTypes(String contentType) {
        assertThatThrownBy(() -> new Asset(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "x", contentType, "org/x/y", UUID.randomUUID()))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("cannot be uploaded");
    }

    @Test
    @DisplayName("a content type with parameters is normalised before the check")
    void normalizesContentTypeParameters() {
        Asset asset = new Asset(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "x", "APPLICATION/PDF; charset=utf-8", "org/x/y", UUID.randomUUID());

        assertThat(asset.getContentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("nothing is deliverable before a scan verdict exists")
    void notDeliverableBeforeScan() {
        Asset asset = newAsset();
        asset.recordStored(1024L, SHA);

        assertThat(asset.isDeliverable()).isFalse();
        assertThat(asset.getStatus()).isEqualTo(Asset.Status.UPLOADED);
    }

    @Test
    @DisplayName("a clean, stored asset is deliverable")
    void deliverableWhenCleanAndStored() {
        assertThat(storedAndScanned().isDeliverable()).isTrue();
    }

    @Test
    @DisplayName("an infected asset is quarantined and never deliverable")
    void quarantinesInfectedAsset() {
        Asset asset = newAsset();
        asset.recordStored(1024L, SHA);
        asset.recordScan(Asset.ScanStatus.INFECTED, "Eicar-Test-Signature");

        assertThat(asset.getStatus()).isEqualTo(Asset.Status.QUARANTINED);
        assertThat(asset.isDeliverable()).isFalse();
    }

    @Test
    @DisplayName("a scanner failure quarantines rather than passing")
    void quarantinesOnScanError() {
        Asset asset = newAsset();
        asset.recordStored(1024L, SHA);
        asset.recordScan(Asset.ScanStatus.ERROR, "timeout");

        assertThat(asset.getStatus()).isEqualTo(Asset.Status.QUARANTINED);
        assertThat(asset.isDeliverable()).isFalse();
    }

    @Test
    @DisplayName("a deleted asset is not deliverable even if it scanned clean")
    void deletedAssetIsNotDeliverable() {
        Asset asset = storedAndScanned();
        asset.delete();

        assertThat(asset.isDeliverable()).isFalse();
        assertThat(asset.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("a checksum from storage must be a 64-character digest")
    void rejectsMalformedChecksum() {
        Asset asset = newAsset();

        assertThatThrownBy(() -> asset.recordStored(10L, "not-a-digest"))
                .isInstanceOf(PlatformExceptions.OperationFailed.class);
    }

    @Test
    @DisplayName("a negative size is rejected")
    void rejectsNegativeSize() {
        Asset asset = newAsset();

        assertThatThrownBy(() -> asset.recordStored(-1L, SHA))
                .isInstanceOf(PlatformExceptions.Validation.class);
    }

    @Test
    @DisplayName("only PUBLIC assets are cacheable by a public edge")
    void onlyPublicAssetsAreCacheable() {
        Asset asset = storedAndScanned();

        assertThat(asset.isPubliclyCacheable()).isFalse();
        asset.setClassification(Asset.Classification.PUBLIC);
        assertThat(asset.isPubliclyCacheable()).isTrue();
        asset.setClassification(Asset.Classification.RESTRICTED);
        assertThat(asset.isPubliclyCacheable()).isFalse();
    }

    @Test
    @DisplayName("a missing project is rejected at construction")
    void requiresProject() {
        assertThatThrownBy(() -> new Asset(UUID.randomUUID(), UUID.randomUUID(), null, null,
                "a.png", "image/png", "org/x/y", UUID.randomUUID()))
                .isInstanceOf(PlatformExceptions.Validation.class);
    }
}
