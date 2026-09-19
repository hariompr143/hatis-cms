# 01 — System Architecture

## 1. What the platform is

HATIS-CMS is one product with three customer-visible surfaces and one
operator-visible surface:

| Surface | Consumer | Purpose |
|---------|----------|---------|
| Management console | Customer admins, editors, developers | Manage organization, projects, content, assets, infrastructure, security, billing |
| Delivery APIs | Customer websites, apps, IoT, third parties | Read published content and assets; write application data |
| Application runtime | Customer's own applications | Business applications built on platform primitives |
| Platform operations console | HATIS operators | Tenant, plan, infrastructure, incident, audit operations |

The same HTTP API serves the console and external customers. There is no
business logic in the frontend; the console is a client of the public API.

## 2. Logical layering

```
┌──────────────────────────────────────────────────────────────────────────┐
│  EDGE        CDN / WAF / TLS termination / custom domains                │
└──────────────────────────────────────────────────────────────────────────┘
                                   │
┌──────────────────────────────────────────────────────────────────────────┐
│  GATEWAY     Ingress → API gateway concerns: authN, rate limit, tenant   │
│              resolution, correlation id, request size, routing           │
└──────────────────────────────────────────────────────────────────────────┘
                                   │
┌──────────────────────────────────────────────────────────────────────────┐
│  CONTROL PLANE                                                           │
│    identity · organization · authorization · billing/entitlements        │
│    domains · certificates · infrastructure · deployment · audit          │
└──────────────────────────────────────────────────────────────────────────┘
                                   │  events / operations
┌──────────────────────────────────────────────────────────────────────────┐
│  DATA PLANE                                                              │
│    cms · assets · workflow · analytics · notifications · integrations    │
│    customer applications · delivery APIs · workers                       │
└──────────────────────────────────────────────────────────────────────────┘
                                   │
┌──────────────────────────────────────────────────────────────────────────┐
│  STATE       PostgreSQL (system of record) · Redis (cache/coordination)  │
│              Object storage (content + assets) · Kafka (durable events)  │
│              Search index (derived, rebuildable) · Analytical store      │
└──────────────────────────────────────────────────────────────────────────┘
```

## 3. Architectural style

**Modular monolith first, services when there is a reason.**

The platform ships as one Spring Boot application assembled from 16 Maven
modules, one per bounded context. Module boundaries are enforced at build time
by ArchUnit rules (`ArchitectureTest` in `hatis-api`):

* a context may only be reached through its `application` package (services) or
  its published events — never through its persistence or domain internals;
* no cyclic dependencies between contexts;
* no `jakarta.persistence` type may cross a context boundary;
* controllers live in `adapter.rest`, never in `domain`.

A context is promoted to a separately deployable service when at least one of
these is true:

| Trigger | Example |
|---------|---------|
| Independent scaling profile | Analytics queries must not compete with CMS delivery |
| Independent failure domain | A tenant's runaway deployment must not degrade content delivery |
| Independent security boundary | Payment handling needs a separate blast radius and audit scope |
| Independent data ownership | A context needs its own database to support customer-owned data residency |
| Independent release cadence / team ownership | Two teams are blocked on each other's releases |

Because contexts communicate through application services and events, promotion
is: extract the module, replace the in-process call with a client implementing
the same port, move its Flyway namespace, deploy. No domain rewrite.

## 4. Runtime topology (SaaS)

```
                         Internet
                            │
                       CDN / WAF
                            │
                    Cloud load balancer
                            │
                  Ingress (TLS, host routing)
                            │
        ┌───────────────────┼────────────────────┐
        ▼                   ▼                    ▼
 hatis-platform       hatis-delivery       customer apps
 (control plane)      (read-optimised      (deployed by the
  N replicas           content/asset APIs)  platform, isolated
        │                   │               namespaces)
        └─────────┬─────────┘
                  ▼
        PostgreSQL (RLS-enforced)      Redis            Kafka
                  │                                      │
                  ▼                                      ▼
          Object storage (S3-compatible)          Workers / relays
                                                         │
                                                  Kubernetes API
                                                         │
                                    ┌────────────────────┼───────────────────┐
                                    ▼                    ▼                   ▼
                             tenant-a-ns           tenant-b-ns         tenant-c-ns
```

Phase 1 runs `hatis-platform` as a single deployment. `hatis-delivery` and the
per-tenant namespaces are introduced in Phase 3 when private/dedicated
deployments land; the domain model does not change.

## 5. Data classification

Every persisted tenant field is classified. Classification drives encryption,
retention, logging, export and backup behaviour.

| Class | Examples | Handling |
|-------|----------|----------|
| `PUBLIC` | Published content, public asset metadata | Cacheable, CDN eligible, no redaction |
| `INTERNAL` | Drafts, dashboards, project metadata | Tenant isolated, not CDN cacheable |
| `CONFIDENTIAL` | Unpublished content, user profiles, analytics data | Tenant isolated, redacted from logs, encrypted at rest |
| `RESTRICTED` | Credentials, tokens, signing keys, payment references | Envelope encrypted, never returned by list endpoints, never logged, access audited |

Rules enforced in code:

* `RESTRICTED` values are never serialized by a controller response. The type
  system helps: secrets are carried in `Secret` / `SecretRef` wrappers that
  override `toString()` to a fixed redacted form.
* Structured log arguments are passed through `LogRedaction` before output.
* Exports declare the highest classification they contain and require the
  matching permission.

## 6. Failure model

Every outbound dependency is treated as unreliable. The platform-wide contract:

| Dependency | Failure handling |
|------------|------------------|
| PostgreSQL | Fail fast, 503 with `Retry-After`; no partial writes (single transaction per use case) |
| Redis | Degrade: rate limiter falls back to local limiting, cache misses through to PostgreSQL |
| Kafka | Durable outbox in PostgreSQL; relay retries with backoff; nothing is lost |
| Object storage | Retry with jitter; multipart for large objects; signed URL generation fails closed |
| Kubernetes API | Operations become `FAILED` with the API error recorded; never silently retried for destructive verbs |
| Cloud/DNS/ACME | Exponential backoff, capped attempts, `operation` row records each attempt |
| Payment provider | Idempotency key on every mutation; never blind-retried; reconciliation job |

Long-running work is always expressed as an **operation** (`202 Accepted` +
`GET /v1/operations/{id}`), never as a blocking HTTP call.
