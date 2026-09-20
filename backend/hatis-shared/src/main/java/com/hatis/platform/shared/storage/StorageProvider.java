package com.hatis.platform.shared.storage;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Provider-neutral object storage.
 *
 * <p>Every byte of customer content reaches storage through this interface, which
 * is what allows one codebase to run against AWS S3, Azure Blob, Google Cloud
 * Storage or a private S3-compatible appliance. Adapters live in
 * {@code hatis-infrastructure}.
 *
 * <p>Hard rules for implementations:
 * <ul>
 *   <li>Never expose a bucket directly; delivery is always through
 *       {@link #presignGet} with a bounded TTL.</li>
 *   <li>Reject keys outside the caller's tenant prefix.</li>
 *   <li>Never log credentials or full signed URLs.</li>
 *   <li>Verify the SHA-256 of what was written and report it back.</li>
 * </ul>
 */
public interface StorageProvider {

    String name();

    /** Writes an object. Implementations must stream, never buffer a whole file in memory. */
    StoredObject put(PutRequest request, InputStream content, long contentLength);

    /** Opens a stream for reading. The caller owns closing it. */
    InputStream get(String key);

    void delete(String key);

    boolean exists(String key);

    ObjectMetadata head(String key);

    /** Issues a time-bounded, object-scoped download URL. TTL is capped by the platform. */
    SignedUrl presignGet(String key, Duration ttl, String downloadFilename);

    /**
     * Issues a time-bounded upload URL so a browser can upload directly to storage
     * without the platform proxying bytes. The key is fixed by the platform, so the
     * client cannot choose where the object lands.
     */
    SignedUrl presignPut(String key, Duration ttl, String contentType);

    /** Copies within the bucket; used for versioning and renditions. */
    StoredObject copy(String sourceKey, String destinationKey);

    java.util.List<String> list(String prefix, int maxKeys);

    /** Verifies provider reachability and credentials. Used by the readiness probe. */
    boolean healthCheck();

    record PutRequest(
            String key,
            String contentType,
            String checksumSha256,
            Map<String, String> metadata,
            StorageClass storageClass) {

        public enum StorageClass {
            STANDARD,
            INFREQUENT_ACCESS,
            ARCHIVE
        }
    }

    record StoredObject(String key, long byteSize, String checksumSha256, String etag, Instant storedAt) {
    }

    record ObjectMetadata(String key, long byteSize, String contentType, String checksumSha256, Instant lastModified) {
    }

    record SignedUrl(String url, Instant expiresAt, String key) {
    }
}
