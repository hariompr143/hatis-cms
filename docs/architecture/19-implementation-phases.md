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
| Schema, 70 tables, 20 migrations | Done | `V1_000`–`V1_019` |
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
| CI green end to end | Done | All eight jobs green in run `36180858909` on commit `b4a01f6`: 404 tests across 40 classes. The run before it, `35509303723` on `3430eaf`, was green at 266 tests; the 138 in between are the workflow, notification, analytics and CMS review tests added since. See §19.6. |
| GitHub integration: inbound webhooks and their management | Done | `hatis-integration`; HMAC-verified push receiver plus create/connect/rotate/disconnect, 48 tests |
| Outbound webhook security core | Done | `hatis-integration`; `WebhookUrlValidator` (SSRF target checks) and `WebhookSigner` (delivery HMAC), 50 tests. Delivery itself is not delivered — see the outbound row below. |
| Outbound webhook persistence | Done | `WebhookEndpoint` and `WebhookDelivery` map `int_webhook_endpoints` and `int_webhook_deliveries`, including the platform's first PostgreSQL `text[]` column; `V1_014` adds the bookkeeping columns deliveries need. `WebhookEndpointPersistenceIT` round-trips both against PostgreSQL 16 under forced RLS, 9 tests. |
| Outbound endpoint management API | Done | `WebhookEndpointService` + `/v1/integrations/webhooks`: register, list, read, update, pause, resume, rotate, delete. The secret is envelope-encrypted under the tenant key and returned in plaintext exactly once. `WebhookEndpointServiceTest`, 11 tests. |
| Outbound webhook delivery, end to end | Done | `OutboxRelay` now publishes to every `EventSink`; `WebhookEventSink` opens delivery records inside the relay's transaction and `WebhookDeliveryWorker` performs the sends outside it. `WebhookEventSinkTest` 5 tests. |
| Outbound webhook dispatcher | Done | `WebhookDispatcher` fans an event out to subscribed endpoints, signs it, records the attempt and schedules exponential-backoff retries; `WebhookDeliveryLog` holds the transactional bookkeeping so no transaction spans an HTTP call. `WebhookDispatcherTest`, 11 tests. Not yet wired to anything — see the outbound row below. |
| Workflow engine | Done | Definitions, instances, tasks and append-only history over `V1_008`; the seeded `editorial_review` template ships in the migration and is parsed by the service that runs it. A transition needs both `workflow:transition` and the assignee the definition names, a move completes the acting task and cancels the rest at that state, and history is written through JDBC because `V1_013` revokes update and delete on it. `WorkflowServiceTest`, `WorkflowDefinitionSpecTest`, `WorkflowDefinitionParserTest`, 48 tests. `WorkflowDefinition` is the platform's first entity in `domain` rather than in the persistence adapter, because a definition is read by the parser and written by nobody; the architecture rule that requires tenant-scoped entities to extend the base type exempts it by name, with the reason — a catalogue row whose `organization_id` is null is not a tenant-owned aggregate — written next to the exemption. |
| Notification | Done | The row is the queue, the delivery record and the inbox: written `PENDING` before anything is sent, so a failed delivery is a row a customer can see rather than a line in a log. `IN_APP` is a no-op, `WEBHOOK` reuses the outbound delivery path, and `EMAIL` exists only where `hatis.notification.email.enabled` is true — where it is false the channel is absent rather than fake, and the notification is recorded as failed. No route takes a user id: the inbox is the caller's, and somebody else's notification reads as not found rather than forbidden. `NotificationTest`, `NotificationServiceTest`, `EmailChannelSenderTest`, 25 tests. |
| Analytics | Done | Metrics in, bucketed storage, series and alert evaluation out. Aggregation happens in SQL with the aggregate function and `date_trunc` unit chosen from closed enums, so no request can put text into the statement; windows are bounded to 90 days because the difference between reading an index and aggregating a table is one query parameter. Alerts evaluate on a schedule per tenant and deliver through notification — one message per recipient per channel, with `WEBHOOK` covered by the published event rather than a second copy. `anl_data_sources` and `anl_datasets` are described by the schema and mapped by nothing, because the platform has no connectors: a connector that returned invented rows would be a placeholder, and platform-event rollups are the same work as the rollup job and belong to Phase 2. `AlertWorkTest`, `AnalyticsServiceTest`, `MetricStoreTest`, `AlertTest`, `DashboardTest` and three domain tests, 57 tests. |
| CMS review flow | Done | Submitting an item starts the seeded editorial definition against it, approving and rejecting are decided by the engine before the item moves, and a rejected item is reworked before it can be reviewed again. Building it exposed a defect that made every content endpoint answer 403 to every caller: the module checked `content:read`, `content:publish` and friends where the catalogue defines `cms:content:read`, `cms:content:publish` and so on, and because authorization matches the code against grants, a code that is not in the catalogue is a closed door rather than a weaker check. `V1_019` adds the codes that were genuinely absent — the same problem existed in the deployment module with `application:*` and `release:*` — and `tools/check_permissions.py` now fails CI for the third instance. `ContentReviewTest`, 8 tests. |
| Outbound webhook transport | Done | `WebClientWebhookTransport` posts to the customer URL, connecting to the address `WebhookUrlValidator.resolveDeliverable` approved rather than resolving a second time, which closes the DNS rebinding gap. The pinning itself is not exercised by any test in this repository — see the outbound row below. |

**Open in Phase 1**

| Area | State |
| --- | --- |
| Integration / outbound — open caveats | Delivered end to end: an event published to the outbox is drained per tenant, fanned out to subscribed endpoints, signed, delivered over an SSRF-guarded transport, and retried with backoff. Three caveats are open and stated rather than buried. **(1)** The address pinning that closes the DNS rebinding gap is argued from the code — no test in this repository opens a real socket. **(2)** A tenant data-key rotation invalidates endpoint secrets written under the previous key; `dek_id` records which key was used, but nothing re-encrypts yet. **(3)** Both background jobs walk every tenant on a fixed interval, which is the wrong shape at a few thousand organizations: most sweeps run one empty query per organization with no queued work. The replacement is a work-claim table listing only organizations with outstanding work; it is not built. Two further items were open and are now closed. `OutboxRelay` used to read `plat_outbox` with no tenant context set — that table carries forced row level security and the migrations explicitly strip `BYPASSRLS` from `hatis_app`, so the query returned an empty list rather than an error, and **no event was ever published** while every metric looked healthy. It is now two beans: `OutboxRelay` walks `plat_tenant_directory`, binds each tenant in turn, and runs a separate unbound pass for platform-wide rows, while `OutboxWork` does the reading and publishing inside that tenant's transaction. The split is the fix, not tidying — Spring applies `@TenantTransactional` through a proxy, so a bean calling its own transactional method gets no transaction and no tenant setting at all, and the only Spring context in this repository wires the datasource and JPA rather than the relay beans, so it would not have caught a self-injected proxy being wrong. `OutboxRelayRlsIT` (11 tests) pins the database behaviour against a real PostgreSQL 16; `OutboxRelayTest` and `OutboxWorkTest` (8 each) pin that work is claimed across a bean boundary and that a failed entry stays queued rather than being marked done. Background jobs could not enumerate tenants from `org_organizations` — it is in the strict tenant list — so `V1_015` adds `plat_tenant_directory` (ids only, no row level security, kept in sync by a trigger). `V1_016` then splits the outbox policy into one policy per command, because `V1_015`'s widened read combined with a strict write left a platform-wide row readable but never publishable, which would have republished it on every sweep forever. |
| Backups | Documented (`17`); no restore drill has been executed, so the RPO/RTO figures are targets, not results. |
| Content review in the console | The API supports submit, approve and reject (`/v1/content/items/{id}/submit`, `/approve`, `/reject`) and the workflow's tasks are readable through `/v1/workflows/tasks`, but the console's content page lists items with a status filter and offers no review actions, so a reviewer works through the API today. |
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

**Verified in GitHub Actions, run `36180858909` on commit `b4a01f6` — every job that
applies to the change green:**

- **Backend (Java 21 / Spring Boot): success.** All 17 modules compile and `mvn verify`
  completes: **404 tests, 0 failures, 0 errors, 0 skipped** across 40 classes, up from
  266 across 25 in the previous green run. The additions are the workflow engine's three
  test classes (48), notification's three (25), analytics' eight (57 — the module had
  none), and `ContentReviewTest` (8), which together are the phase-1 areas that were
  schema-only before. The static source checks now run before Maven and caught nothing
  this time, having been added because `EmailChannelSender` and, earlier, two modules'
  permission codes got past the JDK-only import check and the catalogue.
- **Build and scan container image: success**, with the Trivy scan exiting clean at
  CRITICAL and HIGH.
- **Dependency scan, secret scan and SAST (Semgrep): success.** Frontend and IaC
  validation are skipped rather than failed: the change touches `backend/`, `tools/` and
  the workflow file, and the change filter says so.

Getting there took six pushes, and what each one was for is worth recording, because
five of the six were defects that only a compiler could find:

1. `be84749` — `EmailChannelSender` implemented `ChannelSender` without importing it.
2. `35d6914` — the fix, plus `tools/check_project_imports.py`, which reproduces that
   error locally.
3. `4a7d70e` — a test compared an `AtomicInteger` to an `int`.
4. `81e4efd` — `WorkflowService`'s `orElseThrow` lambda captured a local that is
   reassigned later in the method.
5. `e3110c3` — starting a definition whose initial state is terminal completed the
   instance but published only `workflow.instance.started`.
6. `2702cb5`, `2290c47`, `b4a01f6` — the CMS authorizing item operations at organization
   scope, a conditional expression javac could not infer, and two stubs that mixed a raw
   value with a Mockito matcher.

**Verified earlier in GitHub Actions, run `35509303723` on commit `3430eaf` — all eight
jobs green:**

- **Backend (Java 21 / Spring Boot): success.** All 17 modules compile and
  `mvn verify` completes. The run reports **266 tests, 0 failures, 0 errors,
  0 skipped** across 25 classes: `StorageKeysTest` 7, `AssetTest` 16,
  `ContentBodyValidatorTest` 12, `RichTextSanitizerTest` 16, `ReleaseTest` 9,
  `HexagonalArchitectureTest` 7, `GitHubSignatureVerifierTest` 19,
  `GitHubPushEventTest` 9, `InboundWebhookServiceTest` 11, `IntegrationServiceTest` 9,
  `WebhookEndpointServiceTest` 11, `WebhookDispatcherTest` 13, `WebhookEventSinkTest` 5,
  `WebhookUrlValidatorTest` 42, `WebhookSignerTest` 10, `OutboxRelayTest` 8,
  `OutboxWorkTest` 8, `OutboxEntryPersistenceIT` 10, `WebhookEndpointPersistenceIT` 9,
  `TenantIsolationIT` 9, `OutboxRelayRlsIT` 11 and
  `JsonbDataSourceConfigurationIT` 5, `EntitySchemaValidationIT` 3, `JsonbEntityRoundTripIT` 5, `ApplicationWiringIT` 2,
  the last eight against a real
  PostgreSQL 16 under Testcontainers. Those per-class numbers are published as a
  commit comment on every run, so the count is checkable rather than asserted.
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

**A green build hid a defect that broke six write paths, and only a missing test found it.**
Six entities map a Java `String` onto a PostgreSQL `jsonb` column through
`columnDefinition = "jsonb"`: `OutboxEntry.payload`, `AuditLog.metadata`, `ContentType.schema`,
`ContentVersion.body`, `InboundIntegration.config` and `IdempotencyRecord.responseBody`.
pgjdbc's `stringtype` property defaults to `varchar`, so a `String` parameter reaches the
server typed as `character varying`, and PostgreSQL refuses to assign that to `jsonb`:
`ERROR: column "payload" is of type jsonb but expression is of type character varying`.
Every insert through those mappings failed. Nothing caught it, because nothing in the
repository had ever flushed one of those entities to a database — `TenantIsolationIT`
drives the schema over raw JDBC, where the cast is written by hand, and
`WebhookEndpointPersistenceIT` covers two entities that have no `jsonb` column. The
annotations were correct; the driver was not, and that is not visible from the code.

`OutboxEntryPersistenceIT` was written to settle it rather than to assume it, and it failed
7 of 8 on first run, which is the result that turned a suspicion into a fact. The fix is
pgjdbc's `stringtype=unspecified`, set on the Hikari pool under
`spring.datasource.hikari.data-source-properties` in `application.yml` — on the pool rather
than in the URL, because the URL is supplied per environment and a fix that depends on
whoever configures it remembering is not a fix. One of the ten tests asserts the identical
insert is still *rejected* without the property, so the setting cannot be removed as
apparently-redundant configuration.

That left a narrower version of the same mistake open, and it is worth naming because the
test was green while it existed. `OutboxEntryPersistenceIT` reaches the driver through
`hibernate.connection.stringtype`, on a bootstrap that builds its own connections; production
reaches it through the Hikari pool. Those are different mechanisms, so a green IT proved the
Hibernate half and said nothing about the configuration the application actually boots with —
the fix could have been correct in the test and absent in production. `JsonbDataSourceConfigurationIT`
closes it by reading `spring.datasource.hikari.data-source-properties` back out of
`application.yml` rather than restating it, building a pool from what it reads, and asserting
that a `jsonb` write succeeds through that pool and is rejected through an otherwise
identical one without the properties. Because the expectation is read from the shipped file,
deleting the line fails that test instead of quietly going untested. The chain it exercises is
`HikariConfig.addDataSourceProperty` → `config.getDataSourceProperties()` →
`new DriverDataSource(jdbcUrl, driverClassName, dataSourceProperties, …)` →
`driver.connect(jdbcUrl, driverProperties)`, which is where the property finally reaches
pgjdbc.

That account was still incomplete in one respect, and it is the kind of incompleteness that
is easy to mistake for a finished job. The properties reach `HikariConfig` in the running
application through `@ConfigurationProperties("spring.datasource.hikari")`, and a test that
calls `addDataSourceProperty` by hand exercises the same method the binder calls without
exercising the binder — so a change in how that prefix binds would not have been caught.
`springBindsTheShippedYamlOntoThePool` runs Boot's own `Binder` over the file through
`YamlPropertySourceLoader`, and both write tests now build their pool from the resulting
`HikariConfig`. It also asserts `pool-name` and `maximum-pool-size`, which only arrive if the
whole prefix bound and the `${HATIS_DB_POOL_MAX:20}` placeholder resolved; a map holding one
expected key would satisfy the first assertion on its own. The `PropertySourcesPlaceholdersResolver`
is load-bearing rather than decoration — without it the binder tries to convert the literal
placeholder text into an `int` and fails, which reads as a broken configuration rather than
as a missing step in the test.

**Twenty-five of the twenty-eight entity mappings had never met a database.** The sources
declare 28 `@Entity` classes and, before `EntitySchemaValidationIT`, exactly three had ever
been registered with a Hibernate session — the three an integration test happened to touch.
That mattered because `application.yml` sets `spring.jpa.hibernate.ddl-auto: validate`, so the
first deploy compares all 28 mappings against the migrated schema and refuses to start if any
of them disagrees. Nothing in the repository had ever run that comparison.

It found four, and the first attempt at fixing them was wrong in an instructive way. Setting
`columnDefinition = "char(64)"` on a `char(64)` column did not help; Hibernate reported
`found [bpchar (Types#CHAR)], but expecting [char(64) (Types#VARCHAR)]`. It takes
`columnDefinition` as the expected type *name* verbatim, so the name has to be the one the
server actually reports — `bpchar`, not `char(64)` — while the JDBC type code still comes
from the Java field type. The four jsonb columns already passed validation, which is what
made the mechanism legible: they declare `columnDefinition = "jsonb"` and the name matches.
So the fix names the type as the server reports it — `bpchar` for the two SHA-256 digest
columns, `citext` for `email`, `slug`, `hostname` and `apex_domain`. The schema is not the
side that is wrong: `citext` is what makes those unique indexes case-insensitive, and losing
it would let two tenants hold the same hostname differing only in case.

Two details of the test are worth keeping because both were gotchas. Validation is one
`SessionFactory` per entity, not one for all of them, because Hibernate stops at the first
disagreement it reaches — a single build would have surfaced one finding per cycle, and
`record_hash` and `hostname` stayed hidden behind columns in their own entities until the
sweep was per-entity. And the list has to be printed under a marker the build report
collects, because Maven's one-line summary truncates an assertion message and the failsafe
XML that holds the whole of it is an artifact that cannot be read from here.

Writing that test produced two findings that have nothing to do with `jsonb` and are worth
carrying forward.

The first is that **`target/classes` is not on the failsafe classpath in this build.** A
probe printed by the test shows `application.yml` resolving to `null` both through the class
and through its classloader, and `db/migration` resolving to `null` as well, while the test
class itself loads from `target/test-classes` without difficulty. That is why the
integration tests locate their migrations through a `filesystem:` path rather than
`classpath:db/migration` — the fallback is load-bearing, not defensive, and a new test that
reaches for a main resource through the classloader will get nothing. It is recorded here
because the failure looks like a missing file and is not one.

The second is that **a transaction-local tenant binding does not survive the commit, and row
level security applies to reads.** `set_config('hatis.organization_id', …, true)` scopes the
setting to the transaction, so a row inserted under it and then read back after `commit` is
invisible to the very role that wrote it. Verified against this schema: visible inside the
transaction, `count = 0` for the same role after commit, and still present to a role outside
row level security. The write had succeeded; only the read had lost its binding. An
assertion placed on the far side of a commit therefore reads as a failed write what was a
successful one — which is how the test first failed.

Fixing that surfaced a second fact, found because the test asserted the payload survived the
round trip. It does, but not as text: PostgreSQL's `jsonb` normalises on the way in, sorting
object keys by length and then bytewise and re-spacing the separators. Verified against this
schema — a document written with `eventType` first comes back with `data` first. So the
string stored in `plat_outbox.payload` is never the string the publisher serialised. That is
what the webhook signer signs, and it is what any future payload hash or cache key would have
to be computed over; comparing it against the publisher's output will never match.
`theServerNormalisesTheDocumentBeforeStoringIt` pins it instead of leaving it as a surprise.

All six of those mappings have now been written to a database. `OutboxEntry.payload` is
covered by `OutboxEntryPersistenceIT`; `JsonbEntityRoundTripIT` covers the other five, which
until then had never had a row flushed through Hibernate at all. Sharing a mapping shape with
a covered entity is not the same as being covered by its test — the original defect was
invisible in exactly that way, because the fault was in the driver and not in any one
mapping. Each of those tests asks PostgreSQL what it stored rather than asking Hibernate for
the value back, because a double-encoded write round-trips to Java perfectly and is still
useless: the column holds a jsonb *string* containing JSON, and `column->>'x'` returns null.

**And no Spring context had ever been started at all.** Every test in the repository
constructed its subject directly, which is right for a unit test and wrong for the question
of whether the application boots. So `application.yml` had never been loaded by Spring:
nothing had bound `spring.datasource.hikari` onto a real `HikariConfig`, and nothing had run
the `ddl-auto: validate` the application performs on every start. Two tests came close
without being it — one runs Boot's `Binder` by hand, the other builds a validating
`SessionFactory` by hand — and neither shows the application invoking them.
`ApplicationWiringIT` imports the datasource and JPA autoconfiguration, points
`spring.config.location` at the shipped file, and asserts what arrives: a
`HikariDataSource` carrying `stringtype=unspecified`, pool name `hatis-pool`, maximum pool
size 20, `hibernate.hbm2ddl.auto=validate`, and every entity in the metamodel. The last two
are what stop it passing for the wrong reason: a configuration that silently dropped the
setting, or an entity scan that reached one module, would otherwise look identical to
success. It is a narrow context by design — the full application would also want Redis, an
object storage provider, a secret store and a token issuer, and failing because Redis was
absent would say nothing about the wiring.

**A green secret scan was also not evidence of an absent finding.** Every commit runs the
pipeline twice, on the `push` event and the `pull_request` event, and gitleaks scans only
the new commits on the first but the whole pull request diff on the second. So the push runs
had been green all along while the pull request run reported `rule private-key` at
`TokenService.java:183`. That line is `.replace("-----BEGIN PRIVATE KEY-----", "")`:
`loadSigningKey` reads the signing key from the secret store and strips its PEM armor, so the
source legitimately contains the marker strings, and the rule matches them wherever they
appear. A false positive — but it took three runs to establish that, because "leaks detected,
see job summary" pointed at a summary that was empty, a job log that 404s and an artifact on a
blob host that is not reachable. The secret scan now writes its SARIF into the workspace and
posts rule, file and line as a commit comment; it deliberately does not post the matched
text, since reproducing it on a public repository would publish the value the scan exists to
keep out. `.gitleaks.toml` extends the default ruleset, disables nothing, and scopes the one
allowlist to that file and those `replace()` calls, so an embedded key anywhere — including
elsewhere in that file — still fails.

Two generalisations worth keeping. First, a mapping annotation describes intent; whether the
driver honours it is a fact that has to be observed against a running server. Second, this
was invisible for as long as it was because the test suite covered the layers on either side
of it and not the seam — a raw-JDBC isolation suite that passed over a broken Hibernate
mapping is not evidence about the mapping, and the same is true in reverse.

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

- `tools/check_migrations.py` applies all 20 migrations to a real PostgreSQL 16.2 —
  70 tables, 56 with row level security, `hatis_app` and `hatis_migrator` created.
- `tools/check_entity_schema.py`: 35 entities against 70 tables, 0 mapping gaps, and
  no table declaring a column twice.
- `tools/check_module_deps.py`, `tools/check_imports.py`,
  `tools/check_visibility.py`: 0 unresolved imports, 0 missing module dependencies,
  0 cross-package visibility errors across 230 files.
- `tools/check_permissions.py`: every permission code the code names — 13 constants
  and 46 code-shaped literals — is one the migrations seed. Added after two modules
  shipped checks against codes the catalogue did not define, which denies everybody.
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
