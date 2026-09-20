# 12 — Database and storage abstraction

Two abstractions let the platform run in environments it was not built against:
`StorageProvider` for object storage, and `DatabaseProvider` for tenant databases.
This document states what they promise, what they deliberately do not, and what an
implementer must get right.

## 12.1 StorageProvider

```java
public interface StorageProvider {
    String name();
    StoredObject put(PutRequest request, InputStream content, long contentLength);
    InputStream get(String key);
    void delete(String key);
    boolean exists(String key);
    ObjectMetadata head(String key);
    SignedUrl presignGet(String key, Duration ttl, String downloadFilename);
    SignedUrl presignPut(String key, Duration ttl, String contentType);
    StoredObject copy(String sourceKey, String destinationKey);
    List<String> list(String prefix, int maxKeys);
    boolean healthCheck();
}
```

### Hard rules for implementations

1. **Never expose the bucket.** Reads and writes are presigned and time-bounded.
   There is no method that returns a permanent URL, and `S3StorageProvider` caps the
   TTL at one hour regardless of what the caller asks for.
2. **Reject keys outside the tenant prefix.** `StorageKeys.requireTenantPrefix`
   exists so an adapter can call one method and be correct. A key containing `..`
   is rejected, not normalised.
3. **Never log credentials or a full signed URL.** A signed URL is a bearer
   credential; logging it is logging access to the object.
4. **Report the checksum storage computed**, not the one the client claimed.
   `S3StorageProvider` sends `ChecksumAlgorithm.SHA256` and reads the digest back
   out of the response, so a truncated upload cannot be recorded as intact.
5. **Fail closed.** An unreachable backend is `DependencyUnavailable`, never an
   empty result. `exists()` returning `false` because the network failed would make
   the platform overwrite live data.

### Key layout

Keys are built by `StorageKeys`, never by string concatenation in a service:

```
org/{organizationId}/project/{projectId}/asset/{assetId}/v{n}/{filename}
org/{organizationId}/project/{projectId}/asset/{assetId}/rendition/{profile}
org/{organizationId}/project/{projectId}/content/{itemId}/v{n}/media
org/{organizationId}/export/{exportId}.{ext}
```

The tenant id is the first segment, which gives bucket policies a stable prefix to
match and makes per-customer lifecycle rules and deletion possible without a scan.

### Adding an adapter

Azure Blob and Google Cloud Storage adapters implement the same eleven methods.
Presigning exists in both; `copy` exists in both; nothing in the interface requires
multipart upload, so an adapter is not forced into a lowest-common-denominator
implementation of a feature only S3 has.

## 12.2 DatabaseProvider

Tenants on the `enterprise` and `private` plans get their own database. The port:

```java
public interface DatabaseProvider {
    ProvisionedDatabase provision(ProvisionRequest request);
    void rotateCredentials(UUID instanceId);
    ConnectionState state(UUID instanceId);
    BackupStatus latestBackup(UUID instanceId);
    void deprovision(UUID instanceId);
}
```

Two implementations:

- **`ExternalPostgresProvider`** — the customer supplies a connection string, the
  platform stores it as a `SecretRef`, runs the migrations against it and never
  owns the lifecycle. This is the default for private installs.
- **`ProvisionedPostgresProvider`** — the platform creates the database (RDS,
  Cloud SQL, or a `StatefulSet` in-cluster), owns the credentials, and exposes them
  only through the secret store.

Provisioning is asynchronous: `POST /v1/databases` returns `202` and an operation
id, because creating a managed database takes minutes and an HTTP request must not
be held open for that long.

### Credentials

A tenant database credential is never returned in an API response and never stored
in a column. The row holds a `SecretRef`; the material lives in the secret manager.
`rotateCredentials` writes a new secret version and updates the reference, so
rotation does not require a deploy.

### Migrations

Every tenant database runs the same Flyway migrations, in the same order, from the
same classpath. There is no per-tenant schema divergence: a customer on a dedicated
database has exactly the schema a shared-schema customer has, which is what makes
moving a tenant between isolation modes a data copy rather than a migration
project.

## 12.3 What is deliberately not abstracted

- **The SQL dialect.** PostgreSQL only. A second dialect would mean maintaining two
  migration trees and two sets of RLS semantics; RLS in particular is what makes
  the isolation model provable, and it is a PostgreSQL feature.
- **Object storage semantics beyond the interface.** No versioning, no lifecycle
  policy API, no cross-region replication control. Those are operator concerns,
  configured on the bucket, not modelled in the platform.
- **A generic "provider" registry.** Each port has a small number of real
  implementations. A plugin system for adapters nobody writes is surface area
  without benefit.
