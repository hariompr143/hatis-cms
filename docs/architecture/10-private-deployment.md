# 10 — Private deployment

Phase 1 ships one deployment mode: the platform operator runs the control plane, and
tenants run inside it. Private deployment — a customer running the whole platform
inside their own VPC or data centre — is Phase 2. What follows is the constraint
set that Phase 1 must satisfy so that Phase 2 is a packaging change rather than a
rewrite.

## 10.1 Why this is a design constraint now

Every decision that assumes the platform can reach the public internet, that one
binary serves many customers, or that credentials live in the operator's account
becomes a rewrite when a customer runs the platform themselves. The cheap
constraints are the ones applied while the code is still small.

## 10.2 What must stay true in Phase 1

**No hard dependency on a public cloud.** Every external service sits behind a port
with at least two implementations, one of which runs on-premises:

| Capability | Cloud adapter | Private adapter |
| --- | --- | --- |
| Object storage | AWS S3 | Any S3-compatible endpoint (MinIO, Ceph RGW) via `hatis.storage.s3.endpoint-override` |
| Secrets | AWS Secrets Manager, Vault | Vault on-premises, Kubernetes Secrets, environment |
| Certificates | Let's Encrypt via cert-manager | Imported PEM via `ImportedCertificateProvider` |
| DNS | Cloudflare | Read-only resolver; the customer keeps their zone |
| Database | RDS / Cloud SQL | Any PostgreSQL the customer runs, via `DatabaseProvider` |
| Payments | Stripe | Not required — a private install is licensed, not metered |

**No cross-tenant shared state.** A private installation has exactly one tenant. The
code must therefore not assume that more than one tenant exists: no caching keyed
without a tenant, no global counters, no "first tenant wins" defaults. Row level
security is still enabled, because a single-tenant install that silently drops its
isolation layer is one configuration change away from being a multi-tenant install
with none.

**No phone-home.** Nothing in the platform calls an operator-owned endpoint. No
telemetry egress, no licence check, no update check. Metrics are exposed locally
and scraped by the customer's Prometheus.

**Air-gap tolerance.** The container image contains everything needed to start. No
runtime download of dependencies, drivers, GeoIP databases or signature files that
is not optional and not fatal when unreachable.

## 10.3 Packaging

A private install is the same Helm chart with a different values file:

```yaml
profile: production
platform:
  image:
    repository: registry.customer.internal/hatis/hatis-platform
    tag: "1.4.2"            # never `latest`; the chart refuses it
database:
  external: true            # the customer owns PostgreSQL
redis:
  external: true
events:
  transport: outbox         # Kafka is optional, not required
secretsProvider: vault
storage:
  provider: s3
  s3:
    endpointOverride: "https://minio.storage.internal"
    pathStyleAccess: true
    bucket: hatis-assets
assets:
  scanner:
    mode: clamav
```

`database.external: true` is the normal case: the platform connects to a database
the customer provisions and backs up, rather than creating one. The chart contains
no database subchart, so there is no accidental in-cluster database to protect.

## 10.4 Single-tenant mode

`hatis.tenancy.mode=single` (Phase 2) fixes the tenant at startup:

- sign-up is disabled; the first administrator is created by an install job;
- organization creation is disabled;
- the console hides the organization switcher;
- plan and billing surfaces are replaced by the licence record.

This is a feature-flag surface over the same code, not a separate build. The
`plat_feature_flags` table already carries the flags that drive it.

## 10.5 What Phase 2 adds

Customer VPC peering or a private link, disaster recovery across the customer's
own regions, an operator console that manages many private installs, and upgrade
orchestration that works without outbound internet access. None of it requires
changing a bounded context boundary, which is the point of holding the line now.
