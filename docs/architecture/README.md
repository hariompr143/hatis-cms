# HATIS-CMS — System Architecture

HATIS-CMS is an enterprise business experience, CMS, analytics and application
platform. It is sold as a subscription product and operated both as a
multi-tenant SaaS and as a private installation inside customer infrastructure.

This directory is the normative architecture for the platform. Implementation
must follow it; deviations require an [ADR](../adr/README.md).

| # | Document | §77 requirement |
|---|----------|-----------------|
| 01 | [System architecture](01-system-architecture.md) | Complete system architecture |
| 02 | [Control plane and data plane](02-control-plane-data-plane.md) | Control plane / data plane split |
| 03 | [Bounded contexts](03-bounded-contexts.md) | Bounded contexts |
| 04 | [Modules vs services](04-module-service-boundaries.md) | Modules vs future services |
| 05 | [Database schema](05-database-schema.md) | PostgreSQL schema |
| 06 | [Tenant isolation](06-tenant-isolation.md) | Tenant isolation strategy |
| 07 | [Identity and access management](07-identity-access-management.md) | AuthN / AuthZ architecture |
| 08 | [Deployment architecture](08-deployment-architecture.md) | Deployment architecture |
| 09 | [Kubernetes architecture](09-kubernetes-architecture.md) | Kubernetes architecture |
| 10 | [Private deployment](10-private-deployment.md) | Private deployment architecture |
| 11 | [External CI/CD](11-external-cicd.md) | External CI/CD architecture |
| 12 | [Database and storage abstraction](12-database-storage-abstraction.md) | Provider abstractions |
| 13 | [Event architecture](13-event-architecture.md) | Event architecture |
| 14 | [API standards](14-api-standards.md) | API standards |
| 15 | [Security architecture](15-security-architecture.md) | Security architecture |
| 16 | [Observability](16-observability.md) | Observability architecture |
| 17 | [Backup and disaster recovery](17-backup-disaster-recovery.md) | Backup / DR strategy |
| 18 | [Repository structure](18-repository-structure.md) | Repository structure |
| 19 | [Implementation phases](19-implementation-phases.md) | Implementation phases |

## Non-negotiable engineering priorities

Applied in this order whenever two goals conflict:

1. Security
2. Data isolation
3. Correctness
4. Reliability
5. Recoverability
6. Observability
7. Maintainability
8. Scalability
9. Performance
10. Convenience

## Definition of "production ready"

A capability is production ready only when **all** of the following exist and are
demonstrated by an automated check:

authentication · authorization · tenant isolation · input validation · typed
error handling · structured logging · metrics · auditability (where the action
changes state a customer can be held accountable for) · unit tests · integration
tests · versioned migration · backup/restore consideration · failure handling
for every external dependency · security controls · documentation · deployment
configuration.

"Compiles" is not production ready. "Has a TODO" is not production ready.
