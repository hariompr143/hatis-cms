# 04 — Modules vs Future Services

## 1. Phase 1 deployables

There is exactly **one** deployable service in Phase 1 plus its supporting
runtime. Deliberately. Microservices are an operational response to scale and
team topology, not a design goal.

| Deployable | Contents | Scaling |
|------------|----------|---------|
| `hatis-platform` | All 16 modules (control plane + data plane APIs, workers) | Horizontal, stateless, `N ≥ 2` |
| `hatis-worker` | Same image, `HATIS_ROLE=worker` — outbox relay, webhook delivery worker, alert evaluation | Horizontal, concurrency-limited |
| `hatis-connector` | Customer-side agent for private deployments (Phase 3) | One per customer environment |

The same container image runs API and worker roles, selected by the `HATIS_ROLE`
environment variable. That keeps one artifact to scan, sign and promote, while
letting the two roles scale and fail independently.

Three scheduled jobs exist and all three are `hatis.role=worker` (with
`matchIfMissing`, so a single-role development install still runs them):

| Job | Interval property | Default | What it does |
|-----|-------------------|---------|--------------|
| `OutboxRelay` | `hatis.events.relay-interval` | `2s` | Walks the tenant directory, binds each tenant and publishes its outbox rows |
| `WebhookDeliveryWorker` | `hatis.events.webhook-interval` | `5s` | Sends the deliveries the relay recorded, outside the publishing transaction |
| `AlertScanJob` | `hatis.analytics.alert-interval` | `60s` | Evaluates each tenant's alerts and delivers through notification |

An API replica runs none of them: a second relay would double every event, and a
second alert sweep would double every notification, with each job's own idempotency
the only thing hiding it. Two jobs named in this table until recently do not exist
and are not planned for Phase 1 — operations are executed inline by
`DeploymentService` rather than by a worker, and certificate renewal belongs to
cert-manager, which renews without the platform running. A usage rollup is Phase 2,
and it is the same work as bringing platform events into analytics.

### Platform configuration

These are settings for the installation rather than for a tenant, so they live in
the chart's values (`deploy/helm/hatis-platform/values.yaml`) and reach the pod as
environment variables named after the same keys:

| Setting | Environment variable | Default | Note |
|---------|----------------------|---------|------|
| `hatis.notification.email.enabled` | `HATIS_NOTIFICATION_EMAIL_ENABLED` | `false` | Off means no `EMAIL` sender exists at all |
| `hatis.notification.email.host` | `HATIS_NOTIFICATION_EMAIL_HOST` | — | Required when email is enabled; the chart refuses to render without it |
| `hatis.notification.email.passwordSecret` | `HATIS_NOTIFICATION_EMAIL_PASSWORD_SECRET` | `hatis/smtp/password` | A secret-store **path**, never a password |
| `hatis.analytics.alert-interval` | `HATIS_ANALYTICS_ALERT_INTERVAL` | `60s` | Bounds how late a firing can be |
| `hatis.role` | `HATIS_ROLE` | `api` | `worker` selects the scheduled jobs above |

The SMTP password is read from the secret store at that path per message, and the
environment-backed store derives its variable name from the path
(`hatis/smtp/password` → `HATIS_SMTP_PASSWORD`). The chart mounts the pod variable
under that derived name from the `smtp-password` key of the installation Secret, and
CI renders the chart with email enabled and asserts that the derived name appears in
the manifest — if the two drifted apart, the pod would start and every send would fail
with an empty password. The chart also refuses to render email while the host is
unset, so a half-configured channel fails an install rather than a customer's first
notification.

## 2. Maven module boundaries

```
backend/
├── pom.xml                 # reactor + dependency management (single source of versions)
├── hatis-shared/           # kernel — no business rules
├── hatis-audit/
├── hatis-identity/
├── hatis-organization/
├── hatis-authorization/
├── hatis-cms/
├── hatis-assets/
├── hatis-workflow/
├── hatis-infrastructure/
├── hatis-domains/
├── hatis-deployment/
├── hatis-billing/
├── hatis-analytics/
├── hatis-integration/
├── hatis-notification/
└── hatis-api/              # Spring Boot assembly, security, OpenAPI, migrations
```

Every context module has the same internal shape (hexagonal):

```
com.hatis.platform.<context>/
├── domain/            aggregates, value objects, invariants, domain services
├── application/       use cases (commands/queries), transactions, events emitted
├── port/out/          SPI the context needs from the outside world
└── adapter/
    ├── persistence/   Spring Data repositories, entity mapping
    └── rest/          controllers, request/response records, mapping
```

Rules enforced by `ArchitectureTest`:

```java
// 1. contexts are only reachable through application services
noClasses().that().resideOutsideOfPackage("..cms..")
    .should().dependOnClassesThat().resideInAnyPackage("..cms.domain..", "..cms.adapter..");

// 2. no cycles between contexts
slices().matching("com.hatis.platform.(*)..").should().beFreeOfCycles();

// 3. domain has no framework dependencies
noClasses().that().resideInAPackage("..domain..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..");

// 4. controllers contain no business rules — they call application services only
classes().that().resideInAPackage("..adapter.rest..")
    .should().onlyDependOnClassesThat()
    .resideInAnyPackage("..application..", "..adapter.rest..", "java..", "jakarta.validation..",
                        "org.springframework..", "io.swagger..", "com.hatis.platform.shared..");
```

Rule 3 is what keeps business rules portable when a context becomes a service:
the domain layer compiles with nothing but the JDK.

## 3. Promotion path to services

| Order | Context | Trigger | Effort |
|-------|---------|---------|--------|
| 1 | `analytics` | Analytical queries contend with OLTP delivery; different scaling and memory profile | Move Flyway namespace, expose internal REST/gRPC, deploy separately |
| 2 | `assets` | Upload bandwidth and transformation CPU are bursty; separate ingress limits | Same |
| 3 | `deployment` + `infrastructure` | Provider credentials deserve their own blast radius, mTLS identity and audit scope | Same + dedicated secret store |
| 4 | `billing` | Payment handling: separate compliance scope (PCI adjacent) and network isolation | Same + separate database |
| 5 | `identity` | Becomes the platform IdP for SSO/SAML/SCIM at enterprise scale | Same + HA key storage |

Promotion checklist (must be true before splitting):

- [ ] The context has no cross-context JPA join in any query.
- [ ] All cross-context reads go through an application service or a read model.
- [ ] The context's tables live in a single Flyway namespace with no FK to another context's tables (references are UUIDs, not constraints).
- [ ] Its events are already consumed through the event bus, not in-process listeners that assume a transaction.
- [ ] It has its own integration test suite that runs without the other contexts.
- [ ] It publishes an OpenAPI contract that a client can be generated from.

## 4. Cross-context references

Contexts reference each other by **UUID only**. There are no foreign keys across
context boundaries. This is what makes extraction cheap and is enforced by a
migration review rule:

```sql
-- allowed: inside one context
alter table cms_content_versions
    add constraint fk_content_version_item
    foreign key (content_item_id) references cms_content_items(id);

-- forbidden: cms referencing an assets table
-- alter table cms_content_items
--     add constraint fk_item_asset foreign key (hero_asset_id) references assets(id);
```

Referential integrity across contexts is maintained by the application service
that owns the invariant, plus a periodic reconciliation job that reports
dangling references as a metric (`hatis_reference_integrity_dangling_total`).

## 5. What is explicitly *not* a service yet

* Search: PostgreSQL full-text search over the transactional store until
  measured need (index size, query latency, relevance requirements) justifies
  OpenSearch. The `SearchIndex` port already exists so the swap is an adapter.
* Workflow: in-process. It is a library-shaped engine today; it becomes a
  service when external business applications need to drive it independently.
* Notification: in-process dispatcher with a durable outbox.
