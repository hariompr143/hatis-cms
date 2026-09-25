# 18 — Repository structure

One repository, one CI pipeline, one image. The layout exists so that a bounded
context can be found, reviewed and — later — extracted without a search.

## 18.1 Top level

```
hatis-cms/
├── backend/                  Maven reactor, 16 modules
├── frontend/                 Next.js console (Phase 1, in progress)
├── deploy/
│   ├── docker/               Containerfiles
│   └── helm/                 Chart + per-environment values
├── terraform/                Modules: state tier, platform install
├── docs/architecture/        This document set
├── tools/                    Repository checks that run without a JVM
└── .github/workflows/        ci.yml, release.yml
```

## 18.2 Backend modules

```
hatis-parent (pom)
├── hatis-shared          kernel: ids, errors, tenant context, events, quota, secrets, storage ports
├── hatis-audit           append-only, hash-chained audit trail
├── hatis-identity        users, sessions, MFA, API keys, service accounts
├── hatis-organization    organizations, workspaces, projects, environments, memberships
├── hatis-authorization   permissions, roles, bindings, evaluation
├── hatis-billing         plans, subscriptions, entitlements, quotas, usage
├── hatis-cms             content types, items, versions, publishing
├── hatis-assets          DAM: upload, scan, versions, signed delivery
├── hatis-workflow        definitions, instances, tasks, history
├── hatis-infrastructure  storage bindings, databases, clusters, secrets — plus the real adapters
├── hatis-domains         custom domains, DNS verification, certificates
├── hatis-deployment      applications, releases, rollouts
├── hatis-integration     inbound/outbound integrations, webhooks, deployment tokens
├── hatis-analytics       data sources, datasets, metrics, dashboards, alerts
├── hatis-notification    notifications and delivery
└── hatis-api             assembly: Spring Boot app, Flyway migrations, ArchUnit tests
```

Only `hatis-api` produces an executable. Everything else is a library, which is
what keeps the modular monolith honest: there is no second entry point to smuggle a
dependency through.

## 18.3 Inside a context

```
com/hatis/platform/<context>/
├── domain/               aggregates and invariants. JDK, jakarta.persistence and the kernel only.
├── application/          services, transactions, authorization and quota checks
├── port/out/             outbound interfaces the context needs
└── adapter/
    ├── persistence/      Spring Data repositories
    ├── rest/             controllers and request/response records
    └── <technology>/     concrete outbound adapters
```

`HexagonalArchitectureTest` enforces this at build time:

- `..domain..` depends on no `org.springframework`, `org.hibernate`, Jackson or
  adapter class;
- `..adapter.rest..` never touches `..adapter.persistence..`;
- `..port.out..` depends on no adapter and no provider SDK;
- no dependency cycle between `com.hatis.platform.(*)..` slices;
- the shared kernel imports no context;
- every tenant-owned `@Entity` extends `TenantScopedEntity`.

A rule that is only written down is a rule that will be broken. These fail the
build.

## 18.4 Naming

| Thing | Convention |
| --- | --- |
| Module | `hatis-<context>` |
| Package | `com.hatis.platform.<context>` |
| Table | `<prefix>_<plural>`, prefixes `plat_ idp_ org_ auth_ bill_ infra_ dom_ asset cms_ wf_ dep_ int_ anl_ ntf_ aud_` |
| Metric | `hatis.<context>.<noun>` |
| Event | `<context>.<entity>.<verb>` |
| Error code | `snake_case`, stable, machine-readable |

The table prefix is what makes a schema readable at 70 tables: `dep_` is deployment,
`dom_` is domains, and nothing has to be guessed.

## 18.5 Migrations

`backend/hatis-api/src/main/resources/db/migration/`, one file per concern,
`V1_000` … `V1_019`. Every migration is additive within a release; a destructive
change is a two-release expand-and-contract. A migration that has run anywhere is
never edited — a checksum change on an applied migration is a failure, not a fix —
which is why later corrections (the missing permission codes in `V1_017` and
`V1_019`) arrive as new files rather than as edits to `V1_003`.

`tools/check_entity_schema.py` parses `@Entity`/`@Table`/`@Column` out of the
sources and compares them with the `CREATE TABLE` statements, so a mapping that
would fail Hibernate's startup validation is caught without a JVM. It currently
covers 35 entities against 70 tables with no gaps. It is not a type checker and does
not claim to be — it answers one question exactly. Four sibling checks answer one
question each as well: `check_imports.py` (a JDK type used without its import),
`check_project_imports.py` (the same for a type this repository declares),
`check_visibility.py` (a type used outside the package that may see it),
`check_module_deps.py` (an import no module declares, or one whose module is not a
dependency) and `check_permissions.py` (a permission code the catalogue does not
define, which denies everybody rather than weakening the check). All five run in the
backend CI job before Maven.

## 18.6 Where things go, for the common cases

| I am adding… | It goes in… |
| --- | --- |
| A new aggregate | `<context>/domain/` + a migration + `tools` check |
| A new endpoint | `<context>/adapter/rest/` calling `<context>/application/` |
| A new external dependency | An interface in `<context>/port/out/`, the implementation in `<context>/adapter/<tech>/` |
| A cross-context concept | `hatis-shared`, and only if two contexts genuinely need it |
| A new quota | `QuotaKey` + `bill_plan_limits` rows for every plan |
| A new permission | `auth_permissions` migration + the role grants that should have it |
| A new event | `PlatformEvent.of(...)` in the service that owns the state change |
| A new environment variable | `application.yml` + the Helm container template + the values files |

The last row is the one that gets forgotten. Configuration that exists in code but
not in the chart is configuration that cannot be set in production.

## 18.7 What is not in the repository

No compiled artifacts, no `.env` files, no kubeconfigs, no credentials, no
vendored dependencies. `tools/` contains only scripts that run against the checked
out tree; nothing in it reaches the network.
