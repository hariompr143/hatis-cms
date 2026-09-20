package com.hatis.platform.shared.storage;

import com.hatis.platform.shared.error.PlatformExceptions;

import java.util.UUID;

/**
 * Builds tenant-scoped object storage keys.
 *
 * <p>Centralising key layout is a security control, not a convenience: it makes it
 * impossible for a caller to construct a key outside its own prefix, and it gives
 * bucket policies a stable prefix to match on.
 *
 * <pre>
 * org/{organizationId}/project/{projectId}/asset/{assetId}/v{version}/{filename}
 * org/{organizationId}/project/{projectId}/content/{itemId}/v{version}/media.bin
 * org/{organizationId}/export/{exportId}.zip
 * </pre>
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    public static String asset(UUID organizationId, UUID projectId, UUID assetId, int version, String filename) {
        return "org/" + organizationId + "/project/" + projectId
                + "/asset/" + assetId + "/v" + version + "/" + sanitize(filename);
    }

    public static String rendition(UUID organizationId, UUID projectId, UUID assetId, String profile) {
        return "org/" + organizationId + "/project/" + projectId
                + "/asset/" + assetId + "/rendition/" + sanitize(profile);
    }

    public static String contentMedia(UUID organizationId, UUID projectId, UUID itemId, int version) {
        return "org/" + organizationId + "/project/" + projectId
                + "/content/" + itemId + "/v" + version + "/media";
    }

    public static String export(UUID organizationId, UUID exportId, String extension) {
        return "org/" + organizationId + "/export/" + exportId + "." + sanitize(extension);
    }

    public static String tenantPrefix(UUID organizationId) {
        return "org/" + organizationId + "/";
    }

    /** Fails unless {@code key} is inside the tenant's prefix. Called by every adapter. */
    public static void requireTenantPrefix(UUID organizationId, String key) {
        String prefix = tenantPrefix(organizationId);
        if (key == null || !key.startsWith(prefix)) {
            throw new PlatformExceptions.Forbidden("Storage key is outside the tenant prefix");
        }
        if (key.contains("..")) {
            throw new PlatformExceptions.Forbidden("Storage key must not contain path traversal");
        }
    }

    /**
     * Reduces a user-supplied filename to a safe single path segment. The original
     * name is preserved in metadata for {@code Content-Disposition}, never in the
     * key.
     */
    public static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.startsWith(".")) {
            cleaned = "_" + cleaned;
        }
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(cleaned.length() - 120);
        }
        return cleaned.isBlank() ? "file" : cleaned;
    }
}
