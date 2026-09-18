# 19 — Implementation phases

The build order, what each phase must leave behind, and the rule that governs all
of them: **the project stays runnable after every phase.** A phase that ends with
something that does not start is not finished, whatever else it contains.

## 19.1 Phase order

Each phase is delivered in this internal order, so that a decision is made before
code depends on it:

```
architecture → schema → API contracts → security model → backend
             → frontend → migrations → tests → observability → deployment → docs
             → failure validation
```

"Failure validation" last is deliberate: a phase is not done until someone has
tried to make it fail and watched what happened.

## 19.2 Phase 1 — Multi-tenant SaaS foundation *(in progress)*

**Scope** (§67): identity, multi-tenancy, organizations, projects, CMS, digital
assets, PostgreSQL, object storage, Docker, Kubernetes deployment, custom domains,
TLS, deployment API, GitHub integration, external pipeline integration, environment
variables, secrets, logs, basic analytics, RBAC, audit logs, billing, backups.

**Delivered**

| Area | Status | Evidence |
| --- | --- | --- |
| Architecture (items 1–20) | Done | `docs/architecture/01`–`19` |
| Schema, 69 tables, 14 migrations | Done | `V1_000`–`V1_013` |
| Tenant isolation: predicate + RLS | Done | `TenantIsolationIT` |
| Identity: JWT RS256, BCrypt, TOTP, rotating refresh tokens, API keys | Done | `hatis-identity` |
| Authorization: permissions, roles, bindings, deny overrides | Done | `AuthorizationService` |
| Organizations, projects, environments, memberships | Done | `hatis-organization` |
| CMS: types, versioned items, publish/rollback, sanitising | Done | `hatis-cms`, `ContentBodyValidatorTest` |
| Assets: signed-URL delivery, inline ClamAV, versions | Done | `hatis-assets`, `AssetTest` |
| Domains: DNS-verified ownership, cert-manager, Cloudflare | Done | `hatis-domains` |
| Deployment: pinned releases, 202 + operation polling, Kubernetes API | Done | `hatis-deployment`, `ReleaseTest` |
| Billing: plan-driven quotas and entitlements | Done | `PlanQuotaService` |
| Audit: hash-chained, append-only | Done | `V1_012`, `TenantIsolationIT` |
| Events: transactional outbox, signed webhooks | Done | `hatis-shared/event` |
| Secrets: Env / Vault / AWS adapters, envelope encryption | Done | `hatis-infrastructure` |
| Container image, Helm chart, Terraform modules | Done | `deploy/`, `terraform/`; image builds and Trivy exits clean at CRITICAL,HIGH (§19.6) |
| Architecture boundaries enforced at build time | Done | `HexagonalArchitectureTest` |
| Console (Next.js): sign-in, projects, content, assets, deployments, domains | Done | `frontend/`; lint, typecheck, tests and `next build` green in CI (§19.6) |
| CI green end to end | Done | All eight jobs green in run `35352482484`. See §19.6. |
| GitHub integration: inbound webhooks and their management | Done | `hatis-integration`; HMAC-verified push receiver plus create/connect/rotate/disconnect, 48 tests |
| Outbound webhook security core | Done | `hatis-integration`; `WebhookUrlValidator` (SSRF target checks) and `WebhookSigner` (delivery HMAC), 50 tests. Delivery itself is not delivered — see the outbound row below. |
| Outbound webhook persistence | Done | `WebhookEndpoint` and `WebhookDelivery` map `int_webhook_endpoints` and `int_webhook_deliveries`, including the platform's first PostgreSQL `text[]` column; `V1_014` adds the bookkeeping columns deliveries need. `WebhookEndpointPersistenceIT` round-trips both against PostgreSQL 16 under forced RLS, 9 tests. |
| Outbound endpoint management API | Done | `WebhookEndpointService` + `/v1/integrations/webhooks`: register, list, read, update, pause, resume, rotate, delete. The secret is envelope-encrypted under the tenant key and returned in plaintext exactly once. `WebhookEndpointServiceTest`, 11 tests. |
| Outbound webhook delivery, end to end | Done | `OutboxRelay` now publishes to every `EventSink`; `WebhookEventSink` opens delivery records inside the relay's transaction and `WebhookDeliveryWorker` performs the sends outside it. `WebhookEventSinkTest` 5 tests. |
| Outbound webhook dispatcher | Done | `WebhookDispatcher` fans an event out to subscribed endpoints, signs it, records the attempt and schedules exponential-backoff retries; `WebhookDeliveryLog` holds the transactional bookkeeping so no transaction spans an HTTP call. `WebhookDispatcherTest`, 11 tests. Not yet wired to anything — see the outbound row below. |
| Outbound webhook transport | Done | `WebClientWebhookTransport` posts to the customer URL, connecting to the address `WebhookUrlValidator.resolveDeliverable` approved rather than resolving a second time, which closes the DNS rebinding gap. The pinning itself is not exercised by any test in this repository — see the outbound row below. |

**Not yet delivered in Phase 1**

| Area | State |
| --- | --- |
| Workflow engine | Schema (`V1_008`) and the default template are seeded; there is no service or API. |
| Analytics | Schema (`V1_011`) only. |
| Notification | Schema only. |
| Integration / outbound | Delivered end to end: an event published to the outbox is fanned out to subscribed endpoints, signed, delivered over an SSRF-guarded transport, and retried with backoff. Four caveats are open and stated rather than buried. **(1)** The address pinning that closes the DNS rebinding gap is argued from the code — no test in this repository opens a real socket. **(2)** A tenant data-key rotation invalidates endpoint secrets written under the previous key; `dek_id` records which key was used, but nothing re-encrypts yet. **(3)** `OutboxRelay` reads `plat_outbox` with no tenant context set, and that table carries forced row level security, so whether the relay can see any rows depends on the database role the application connects as — configured per environment and covered by no test. If that role lacks `BYPASSRLS`, the relay and webhook fan-out with it silently see nothing. **(4)** `WebhookDeliveryWorker` scans every organization each interval, because a cross-tenant query for due work is not expressible under row level security; the right fix is a small unsecured work-claim table. |
| Backups | Documented (`17`); no restore drill has been executed, so the RPO/RTO figures are targets, not results. |
| OWASP dependency-check | Runs only when an `NVD_API_KEY` secret exists; without one it skips with a notice, because dependency-check cannot fetch the NVD cache inside a job timeout unkeyed. Trivy is the gate that actually fails a build. See §19.6. |

## 19.3 Phase 2 — Enterprise

Advanced workflow (parallel gates, scheduled transitions, SLA escalation), advanced
analytics, SSO (SAML, OIDC) and SCIM provisioning, private deployment packaging,
customer VPC connectivity, multi-region disaster recovery.

Entry criterion: Phase 1 running in production with real tenants. Extracting
services before then optimises a shape that has not met load.

## 19.4 Phase 3 — Platform

CRM, ERP and BI connectors, AI-assisted content, an extension marketplace,
operator console for many private installs.

## 19.5 Explicitly deferred

Kubernetes operator (until the deployment model is stable — §09), a plugin runtime,
a second SQL dialect, a proprietary CI system, and any AI feature that would put
customer content into a third-party model without a per-tenant control.

## 19.6 Current verification status

Stated plainly, because a claim of "done" without a named check is worth nothing.

**Verified in GitHub Actions, run `35352482484` on commit `e498373` — all eight jobs
green:**

- **Backend (Java 21 / Spring Boot): success.** All 17 modules compile and
  `mvn verify` completes. Run `35352482484` reports **214 tests, 0 failures, 0 errors,
  0 skipped** across 17 classes: `StorageKeysTest` 7, `AssetTest` 16,
  `ContentBodyValidatorTest` 12, `RichTextSanitizerTest` 16, `ReleaseTest` 9,
  `HexagonalArchitectureTest` 7, `GitHubSignatureVerifierTest` 19,
  `GitHubPushEventTest` 9, `InboundWebhookServiceTest` 11, `IntegrationServiceTest` 9,
  `WebhookEndpointServiceTest` 11, `WebhookDispatcherTest` 13, `WebhookEventSinkTest` 5,
  `WebhookUrlValidatorTest` 42, `WebhookSignerTest` 10, `WebhookEndpointPersistenceIT` 9
  and `TenantIsolationIT` 9, the last two against a real PostgreSQL 16 under
  Testcontainers. Those per-class numbers are
  published as a commit comment on every run, so the count is checkable rather than
  asserted.
- **Build and scan container image: success.** The image builds from
  `deploy/docker/Dockerfile.platform` and the Trivy scan over it exits clean at
  `severity: CRITICAL,HIGH` with `ignore-unfixed: true`.
- **IaC validation: success.** `helm lint --strict`, `helm template` with the
  production values, and `terraform validate` for both modules.
- **Frontend (Next.js / TypeScript): success.** Lint, typecheck, tests, build.
- **SAST (Semgrep) and secret scan: success.**
- **Dependency scan: success, by skipping.** The job checks for an `NVD_API_KEY`
  secret and, finding none, skips the OWASP scan with a `::notice` that says what is
  missing and how to enable it. It is not a silent skip. dependency-check 10.x cannot
  download the NVD cache inside a job timeout without a key: bounded at 25 minutes it
  still reached the timeout (run `35323914445`, 25m38s), and unbounded it had run
  1h9m32s. A timeout marks the job *and the whole run* cancelled, which turned one
  advisory scan into a red X on an otherwise green pipeline.

**Closing the image scan took four attempts, and three of them were wrong.**
The scan reported 61 fixable findings (9 critical, 52 high), every one of them inside
`app.jar` rather than in the base image, so it was a dependency problem and not a
Dockerfile problem. Spring Boot 3.3.5's managed transitives were all behind their
fixes, and two of the criticals — `spring-security-web` CVE-2026-22732 and
`spring-boot` CVE-2026-40973 — cannot be patched from underneath Boot at all. Moving
to Boot 3.5.14 brought 61 down to 39; the rest needed the overrides below.

**Version overrides must be declared above the Spring Boot BOM.** Boot is consumed in
`backend/pom.xml` as an *imported* BOM, not as the POM's parent. An imported BOM
resolves its own properties in its own context, so `<tomcat.version>` and
`<netty.version>` in the importing POM are inert — the scan proved it by returning
`tomcat-embed-core 10.1.54` and `netty 4.1.132.Final`, exactly what Boot 3.5.14
manages, with the properties set to 10.1.58 and 4.1.137.Final. That override trick only
works when a project inherits `spring-boot-starter-parent`. Maven resolves
`dependencyManagement` first-declaration-wins, so `backend/pom.xml` imports
`spring-framework-bom`, `spring-security-bom`, `netty-bom`, `jackson-bom` and
`micrometer-bom`, and pins the artifacts that have no BOM, in thirteen entries placed
*above* the `spring-boot-dependencies` import. Each entry carries the CVE it closes.

**"The scanner names this version as the fix" is not "this version is obtainable."**
The three remaining criticals all cited Tomcat 10.1.58. Tomcat never published it —
`tomcat-embed-core`'s `maven-metadata.xml` on Maven Central, lastUpdated
`20260915192844`, runs 10.1.57 straight to 10.1.59, and Tomcat's own 10.1 changelog
carries 10.1.58 as "not released". 10.1.60 is used instead. Two further notes: Spring
Data versions its BOM by release train (`2025.0.x`) while `spring-data-commons` is
`3.5.x`, so `spring-data-bom:3.5.12` does not exist and the module must be pinned
directly; and a blank severity cell in Trivy's table inherits the row above it, which
is how CVE-2026-65182 was first misread as HIGH when it is CRITICAL.

**Inbound webhooks and a constraint worth recording.** `hatis-integration` now receives
GitHub push deliveries: the signature is verified over the raw body before anything is
parsed, the delivery is audited, and a platform event is published. Implementing it
surfaced a constraint that will apply to any future pre-authentication lookup.
`int_integrations` is under forced row level security, so a transaction with no tenant
bound sees zero rows — the query that would identify the tenant is itself blocked by the
isolation layer. There was no precedent to copy: `ApiKeyRepository.findByKeyHash` has
the same shape and is never called, so API key authentication is not actually wired.
The organization therefore arrives as a path segment, used only to scope one RLS-bound
read and then verified against the row; it never authorizes anything, since acceptance
requires an HMAC under that integration's secret. The reasoning is in
`InboundWebhookService`, not only here.

**Signing secrets are stored retrievably, and that is a deliberate difference from API
keys.** An API key is only ever compared, so a SHA-256 hash is enough and a database leak
yields nothing usable. Verifying an HMAC requires the actual key, so the platform must be
able to read the webhook secret back; it therefore lives in the secret manager and only a
path is persisted. The consequence is stated plainly rather than glossed: a secret-store
compromise exposes working signing secrets. That is the boundary the secret manager exists
to hold, and the reason its adapters are Vault or a cloud KMS rather than a file. Rotation
deletes the old secret immediately with no grace window, because a window in which both
secrets are accepted is what makes rotation ineffective against a leaked one.

**Verified locally:**

- `tools/check_migrations.py` applies all 14 migrations to a real PostgreSQL 16.2 —
  69 tables, 56 with row level security, `hatis_app` and `hatis_migrator` created.
- `tools/check_entity_schema.py`: 25 entities against 69 tables, 0 mapping gaps, and
  no table declaring a column twice.
- `tools/check_module_deps.py`, `tools/check_imports.py`,
  `tools/check_visibility.py`: 0 unresolved imports, 0 missing module dependencies,
  0 cross-package visibility errors across 138 files.
- Console: the frontend CI job is green — lint, typecheck, its Vitest suite and
  `next build`. It was also run locally in earlier sessions with the same result.

**Found by running things rather than reading them.** Three defects below would have
reached production and are recorded because the checks that caught them are now part
of the build:

- `V1_003` seeded the eight system roles through a CTE and then joined it from the
  *next* statement. A CTE is scoped to the statement that opens it, so every Flyway
  run failed with `relation "system_roles" does not exist`. Found by applying the
  migrations to a real database.
- `dep_releases` declared two columns named `version` — the release's semantic
  version and `BaseEntity`'s optimistic lock. PostgreSQL rejects that outright.
- `TenantIsolationIT` bound the tenant with a transaction-local `set_config` on an
  autocommit connection, so the setting was discarded before the next statement and
  every query saw zero rows. Three isolation tests were passing because nothing was
  visible to anyone, which is not the same as passing.

**Environment limits:** this sandbox has no `javac`, Maven, Docker or kubectl, and
`curl` cannot reach Maven Central, so the backend is compiled and tested only in CI.
Maven Central *is* readable through a document-fetch path, which is how the Tomcat
metadata above was checked instead of guessed. `pgserver` provides a real PostgreSQL
16.2 locally, which is how the migrations and the row level security behaviour were
checked directly.

## 19.7 Definition of done for a phase

A phase is done when all of these are true, not when the code is written:

1. `mvn verify` is green in CI, named by run id.
2. The tenant isolation test passes against a real database.
3. The Helm chart renders for staging and production.
4. The container image builds and its vulnerability scan has no unaccepted critical.
5. The failure cases in §17.6 that apply to the phase have been exercised.
6. The documents that describe the phase match what was built.
7. Nothing in the tree is a placeholder, a TODO, or a stub that returns success.
