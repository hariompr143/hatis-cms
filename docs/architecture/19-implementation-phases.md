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
| CI green | **Unverified.** See §19.6. |

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

Stated plainly, because a claim of "done" without a named check is worth nothing:

- **Verified locally, backend:** Java syntax across 130 files (Chevrotain parser, 0
  failures); all 17 POMs well-formed; entity-to-schema mapping across 25 entities
  and 69 tables with 0 gaps (`tools/check_entity_schema.py`); Helm values files
  parse as YAML; every architecture invariant the ArchUnit rules assert was checked
  by grep before the rule was written.
- **Verified locally, console:** `npm run lint` clean with `--max-warnings 0`,
  `tsc --noEmit` clean, 41 Vitest tests passing across 5 files, and
  `next build` producing 10 routes. These are real runs, not inspections.
- **Not verified anywhere:** compilation and test execution. This sandbox has no
  `javac`, no Maven, no Docker, no PostgreSQL and no reachable Maven repository, so
  `mvn verify` has never run against the current tree. The tests in §15.8 are
  written and are expected to run in GitHub Actions, but **no CI run has yet
  executed them**.
- **Blocked:** the GitHub token in this session has expired. `gh auth status`
  reports the token is no longer valid and `git push` cannot authenticate, so the
  work committed on `arena/01a0abb1-hatis-cms` is not on the remote and CI cannot
  be triggered. Reconnecting GitHub in Arena is the prerequisite for any CI claim.

Two earlier defects were found this way and are fixed but still unverified in CI:
`spring-kafka` was declared under the wrong groupId, and `nimbus-jose-jwt` was not
pinned. Both were caught by a container-image build step after the backend job
reported success, because that job piped Maven into `tee` without `pipefail`. The
pipeline steps now use `set -euo pipefail`.

## 19.7 Definition of done for a phase

A phase is done when all of these are true, not when the code is written:

1. `mvn verify` is green in CI, named by run id.
2. The tenant isolation test passes against a real database.
3. The Helm chart renders for staging and production.
4. The container image builds and its vulnerability scan has no unaccepted critical.
5. The failure cases in §17.6 that apply to the phase have been exercised.
6. The documents that describe the phase match what was built.
7. Nothing in the tree is a placeholder, a TODO, or a stub that returns success.
