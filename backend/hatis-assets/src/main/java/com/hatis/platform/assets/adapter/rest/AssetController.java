package com.hatis.platform.assets.adapter.rest;

import com.hatis.platform.assets.application.AssetService;
import com.hatis.platform.assets.domain.Asset;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.error.PlatformExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Asset API.
 *
 * <p>Uploads return metadata plus a signed URL — bytes never traverse this process —
 * and downloads always hand back a URL instead of proxying, which keeps large
 * transfers out of the API tier's memory.
 */
@RestController
@RequestMapping("/v1/assets")
@Tag(name = "Assets", description = "Digital asset management")
public class AssetController {

    private final AssetService assets;

    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload an asset")
    public ResponseEntity<AssetService.AssetSummary> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam UUID projectId,
            @RequestParam(required = false) UUID folderId) {
        if (file.isEmpty()) {
            throw new PlatformExceptions.Validation("The uploaded file is empty", Map.of("field", "file"));
        }
        try {
            var stored = assets.upload(projectId, folderId, file.getOriginalFilename(),
                    file.getContentType(), file.getInputStream(), file.getSize());
            return ResponseEntity.status(HttpStatus.CREATED).body(stored);
        } catch (java.io.IOException e) {
            throw new PlatformExceptions.OperationFailed("The uploaded file could not be read");
        }
    }

    @PostMapping("/{assetId}/versions")
    @Operation(summary = "Upload a new version of an asset")
    public ResponseEntity<AssetService.AssetSummary> uploadVersion(
            @PathVariable UUID assetId,
            @RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new PlatformExceptions.Validation("The uploaded file is empty", Map.of("field", "file"));
        }
        try {
            var stored = assets.uploadVersion(assetId, file.getOriginalFilename(), file.getContentType(),
                    file.getInputStream(), file.getSize());
            return ResponseEntity.status(HttpStatus.CREATED).body(stored);
        } catch (java.io.IOException e) {
            throw new PlatformExceptions.OperationFailed("The uploaded file could not be read");
        }
    }

    @GetMapping
    @Operation(summary = "List assets in a project")
    public PageResponse<AssetService.AssetSummary> list(@RequestParam UUID projectId,
                                                        @RequestParam(required = false) UUID folderId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "25") int size) {
        return assets.list(projectId, folderId, page, size);
    }

    @GetMapping("/{assetId}")
    @Operation(summary = "Get asset metadata")
    public AssetService.AssetSummary get(@PathVariable UUID assetId) {
        return assets.get(assetId);
    }

    @PostMapping("/{assetId}/download-url")
    @Operation(summary = "Issue a short-lived signed download URL")
    public AssetService.DownloadLink downloadUrl(@PathVariable UUID assetId,
                                                 @RequestParam(required = false) Long validForSeconds) {
        return assets.downloadUrl(assetId, validForSeconds == null ? null : Duration.ofSeconds(validForSeconds));
    }

    @PostMapping("/{assetId}/classification")
    @Operation(summary = "Set the sensitivity classification of an asset")
    public AssetService.AssetSummary setClassification(@PathVariable UUID assetId,
                                                       @Valid @RequestBody ClassificationRequest request) {
        return assets.setClassification(assetId, request.classification());
    }

    @PostMapping("/{assetId}/rescan")
    @Operation(summary = "Re-run the malware scan for an asset")
    public AssetService.AssetSummary rescan(@PathVariable UUID assetId) {
        return assets.rescan(assetId);
    }

    @GetMapping("/{assetId}/integrity")
    @Operation(summary = "Verify the stored bytes against the recorded checksum")
    public Map<String, Object> verifyIntegrity(@PathVariable UUID assetId) {
        return Map.of("assetId", assetId, "matches", assets.verifyIntegrity(assetId));
    }

    @DeleteMapping("/{assetId}")
    @Operation(summary = "Soft delete an asset")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID assetId) {
        assets.delete(assetId);
    }

    public record ClassificationRequest(@NotNull Asset.Classification classification) {
    }
}
