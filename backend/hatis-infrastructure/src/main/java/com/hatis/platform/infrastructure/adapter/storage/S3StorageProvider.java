package com.hatis.platform.infrastructure.adapter.storage;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * S3-compatible {@link StorageProvider}.
 *
 * <p>Runs against AWS S3 and against any S3-compatible service (MinIO, Ceph RGW,
 * Cloudflare R2, Wasabi) through the endpoint override, so a private installation
 * needs no external cloud account to have a working DAM.
 *
 * <p>Security properties that are enforced here rather than left to configuration:
 * <ul>
 *   <li>every object is written with server-side encryption;</li>
 *   <li>no method in this class returns a public URL — reads are presigned and
 *       time-bounded;</li>
 *   <li>the SHA-256 is computed by S3 during the write and returned, so a truncated
 *       upload cannot be recorded as intact;</li>
 *   <li>credentials come from the ambient AWS credential chain (IAM role, IRSA),
 *       never from configuration or Git.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "hatis.storage.provider", havingValue = "s3", matchIfMissing = true)
public class S3StorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(S3StorageProvider.class);

    /** The platform caps signed URL lifetimes; a caller cannot ask for longer. */
    private static final Duration MAX_PRESIGN_TTL = Duration.ofHours(1);

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3StorageProvider(Environment environment) {
        String bucket = environment.getProperty("hatis.storage.s3.bucket");
        if (bucket == null || bucket.isBlank()) {
            throw new PlatformExceptions.StateConflict(
                    "S3 storage is selected but hatis.storage.s3.bucket is not configured");
        }
        this.bucket = bucket;
        Region region = Region.of(environment.getProperty("hatis.storage.s3.region", "us-east-1"));
        S3Client.Builder clientBuilder = S3Client.builder().region(region);
        S3Presigner.Builder presignerBuilder = S3Presigner.builder().region(region);
        String endpoint = environment.getProperty("hatis.storage.s3.endpoint-override");
        if (endpoint != null && !endpoint.isBlank()) {
            URI uri = URI.create(endpoint);
            clientBuilder.endpointOverride(uri)
                    .forcePathStyle(Boolean.parseBoolean(
                            environment.getProperty("hatis.storage.s3.path-style-access", "true")));
            presignerBuilder.endpointOverride(uri);
        }
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    @Override
    public String name() {
        return "s3";
    }

    @Override
    public StoredObject put(PutRequest request, InputStream content, long contentLength) {
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(request.key())
                .contentType(request.contentType())
                .serverSideEncryption(ServerSideEncryption.AES256)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256);
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            builder.metadata(request.metadata());
        }
        if (request.checksumSha256() != null && !request.checksumSha256().isBlank()) {
            // S3 rejects the write when the digest it computes does not match this
            // value, which is the strongest guarantee available without a second
            // round trip.
            builder.checksumSHA256(request.checksumSha256());
        }
        try {
            PutObjectResponse response = client.putObject(builder.build(),
                    RequestBody.fromInputStream(content, contentLength));
            return new StoredObject(request.key(), contentLength,
                    toHex(response.checksumSHA256()), response.eTag(), Instant.now());
        } catch (Exception e) {
            // The exception message may embed request details; the key is safe to
            // log, the content never is.
            log.warn("Object write failed for key {}: {}", request.key(), e.getClass().getSimpleName());
            throw new PlatformExceptions.DependencyUnavailable("object-storage", e.getMessage());
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return response;
        } catch (NoSuchKeyException e) {
            throw new PlatformExceptions.NotFound("Storage object", key);
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("object-storage", e.getMessage());
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(builder -> builder.bucket(bucket).key(key));
        } catch (Exception e) {
            log.warn("Object delete failed for key {}: {}", key, e.getClass().getSimpleName());
            throw new PlatformExceptions.DependencyUnavailable("object-storage", e.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("object-storage", e.getMessage());
        }
    }

    @Override
    public ObjectMetadata head(String key) {
        try {
            HeadObjectResponse response = client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return new ObjectMetadata(key, response.contentLength(), response.contentType(),
                    toHex(response.checksumSHA256()), response.lastModified());
        } catch (NoSuchKeyException e) {
            throw new PlatformExceptions.NotFound("Storage object", key);
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("object-storage", e.getMessage());
        }
    }

    @Override
    public SignedUrl presignGet(String key, Duration ttl, String downloadFilename) {
        Duration effective = capTtl(ttl);
        GetObjectRequest.Builder builder = GetObjectRequest.builder().bucket(bucket).key(key);
        if (downloadFilename != null && !downloadFilename.isBlank()) {
            // The original filename is quoted here and is never part of the key, so
            // a hostile filename cannot escape the header or create a path.
            builder.responseContentDisposition(
                    "attachment; filename=\"" + downloadFilename.replace("\"", "") + "\"");
        }
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(effective)
                .getObjectRequest(builder.build())
                .build();
        try {
            return new SignedUrl(presigner.presignGetObject(request).url().toString(),
                    Instant.now().plus(effective), key);
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("object-storage", e.getMessage());
        }
    }

    @Override
    public SignedUrl presignPut(String key, Duration ttl, String contentType) {
        Duration effective = capTtl(ttl);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(effective)
                .putObjectRequest(request)
                .build();
        try {
            return new SignedUrl(presigner.presignPutObject(presignRequest).url().toString(),
                    Instant.now().plus(effective), key);
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("object-storage", e.getMessage());
        }
    }

    /**
     * Server-side copy.
     *
     * <p>Single-part copy, which covers objects up to 5 GiB — larger than any asset
     * the upload path accepts. A rendition job that must handle bigger objects
     * needs multipart copy, and would add it here rather than in a caller.
     */
    @Override
    public StoredObject copy(String sourceKey, String destinationKey) {
        try {
            var response = client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(sourceKey)
                    .destinationBucket(bucket)
                    .destinationKey(destinationKey)
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build());
            HeadObjectResponse head = client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(destinationKey).build());
            return new StoredObject(destinationKey, head.contentLength(), toHex(head.checksumSHA256()),
                    response.copyObjectResult().eTag(), Instant.now());
        } catch (NoSuchKeyException e) {
            throw new PlatformExceptions.NotFound("Storage object", sourceKey);
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("object-storage", e.getMessage());
        }
    }

    @Override
    public List<String> list(String prefix, int maxKeys) {
        try {
            return client.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .maxKeys(Math.min(Math.max(maxKeys, 1), 1000))
                            .build())
                    .contents()
                    .stream()
                    .map(S3Object::key)
                    .toList();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("object-storage", e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (Exception e) {
            log.warn("Object storage health check failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static Duration capTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofMinutes(10);
        }
        return ttl.compareTo(MAX_PRESIGN_TTL) > 0 ? MAX_PRESIGN_TTL : ttl;
    }

    /** S3 reports the SHA-256 checksum base64-encoded; the platform stores hex. */
    static String toHex(String base64Checksum) {
        if (base64Checksum == null || base64Checksum.isBlank()) {
            return "";
        }
        try {
            byte[] digest = Base64.getDecoder().decode(base64Checksum);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            // Some S3-compatible services return hex directly.
            return base64Checksum.toLowerCase(Locale.ROOT);
        }
    }

    /** Convenience for tests and for the readiness probe. */
    Map<String, String> describe() {
        return Map.of("provider", name(), "bucket", bucket);
    }
}
