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
| Container image, Helm chart, Terraform modules | Done | `deploy/`, `terraform/` |
| Architecture boundaries enforced at build time | Done | `HexagonalArchitectureTest` |
| Console (Next.js): sign-in, projects, content, assets, deployments, domains | Done | `frontend/`, 41 tests, lint, typecheck and `next build` all green locally |

**Not yet delivered in Phase 1**

| Area | State |
| --- | --- |
| Workflow engine | Schema (`V1_008`) and the default template are seeded; there is no service or API. |
| Analytics | Schema (`V1_011`) only. |
| Notification | Schema only. |
| Integration / GitHub inbound | Schema and deployment tokens only; no webhook receiver. |
| Backups | Documented (`17`); no restore drill has been executed, so the RPO/RTO figures are targets, not results. |
| CI green | Backend, frontend, IaC, SAST and secret scan green in run `35313278777`. See §19.6. |

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

**Verified in GitHub Actions, run `35313278777` on commit `2c57cc0`:**

- **Backend (Java 21 / Spring Boot): success.** All 17 modules compile and
  `mvn verify` completes, which is 67 unit tests and 9 tenant-isolation integration
  tests: `StorageKeysTest` 7, `AssetTest` 16, `ContentBodyValidatorTest` 12,
  `RichTextSanitizerTest` 16, `ReleaseTest` 9, `HexagonalArchitectureTest` 7, and
  `TenantIsolationIT` 9 against a real PostgreSQL 16 under Testcontainers.
- **IaC validation: success.** `helm lint --strict`, `helm template` with the
  production values, and `terraform validate` for both modules.
- **Frontend (Next.js / TypeScript): success.** Lint, typecheck, tests, build.
- **SAST (Semgrep) and secret scan: success.**
- **Not confirmed:** the dependency scan and the container image build were still
  running when this session's GitHub token expired. No claim is made about them.

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

**Environment limits, unchanged:** this sandbox has no `javac`, Maven, Docker or
kubectl, and Maven Central is unreachable, so the backend is verified only in CI.
`pgserver` provides a real PostgreSQL 16.2 locally, which is how the migrations and
the row level security behaviour were checked directly.

## 19.7 Definition of done for a phase

A phase is done when all of these are true, not when the code is written:

1. `mvn verify` is green in CI, named by run id.
2. The tenant isolation test passes against a real database.
3. The Helm chart renders for staging and production.
4. The container image builds and its vulnerability scan has no unaccepted critical.
5. The failure cases in §17.6 that apply to the phase have been exercised.
6. The documents that describe the phase match what was built.
7. Nothing in the tree is a placeholder, a TODO, or a stub that returns success.
