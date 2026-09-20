# 03 — Bounded Contexts

Contexts are derived from *who owns which decision*, not from tables. Each
context owns its ubiquitous language, its aggregates, its events and its
persistence.

## 1. Context map

```
                              ┌────────────────┐
                              │   identity     │  users, credentials, MFA,
                              │  (upstream)    │  API keys, service accounts
                              └───────┬────────┘
                                      │ UserId (conformist)
        ┌─────────────────────────────┼──────────────────────────────┐
        ▼                             ▼                              ▼
┌───────────────┐            ┌────────────────┐             ┌───────────────┐
│ organization  │◀───────────│ authorization  │             │    audit      │
│ orgs, projects│  membership│ roles, policy, │             │ (all contexts │
│ environments  │  lookup    │ entitlements   │             │  publish to)  │
└──────┬────────┘            └───────┬────────┘             └───────────────┘
       │ ProjectId / EnvironmentId   │ Decision
   ┌───┴──────────┬───────────┬──────┴──────┬──────────────┬─────────────┐
   ▼              ▼           ▼             ▼              ▼             ▼
┌──────┐    ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐
│ cms  │───▶│ workflow │ │  assets  │ │ deployment│ │ analytics │ │  billing  │
└──┬───┘    └──────────┘ └──────────┘ └─────┬─────┘ └───────────┘ └───────────┘
   │                                        │
   │              ┌────────────────┐        ▼
   └─────────────▶│ infrastructure │  storage · database · kubernetes ·
       asset refs │  (upstream)    │  dns · certificate providers
                  └───────┬────────┘
                          ▼
                  ┌────────────────┐   ┌──────────────┐   ┌────────────────┐
                  │    domains     │   │ integration  │   │ notification   │
                  │ domains + TLS  │   │ SCM, webhooks│   │ email, in-app  │
                  └────────────────┘   └──────────────┘   └────────────────┘
```

| Relationship | Meaning |
|--------------|---------|
| `cms → workflow` | Customer/supplier: content approval asks the workflow context to run a process |
| `cms → assets` | Conformist: content references asset ids; the CMS never stores asset bytes |
| `deployment → infrastructure` | Anti-corruption layer: the deployment context speaks its own language (`DesiredWorkload`) and adapters translate to Kubernetes |
| `* → audit` | Published language: every context emits `AuditRecord` through one port |
| `authorization → organization` | Customer/supplier via the `MembershipLookup` port (no reverse dependency) |

## 2. Context responsibilities

### identity
Owns *who a principal is*. Aggregates: `User`, `Credential`, `MfaEnrolment`,
`RefreshToken`, `ApiKey`, `ServiceAccount`. Publishes `UserCreated`,
`UserDisabled`, `CredentialRotated`, `MfaEnrolled`, `SuspiciousSignIn`.
Does **not** know what a principal may do (that is `authorization`) or which
tenant they belong to (that is `organization`).

### organization
Owns the tenant resource hierarchy: `Organization → Workspace → Project →
Environment`. Publishes `OrganizationCreated`, `ProjectCreated`,
`EnvironmentCreated`, `MembershipChanged`.

### authorization
Owns `Role`, `Permission`, `RoleBinding`, `PolicyDecision`. Evaluates
`(subject, permission, resource)` → `allow | deny + reason`. Pure domain logic;
no I/O except reading bindings. Publishes `RoleBindingChanged`.

### billing
Owns `Plan`, `Subscription`, `Entitlement`, `UsageRecord`, `Invoice`,
`PaymentTransaction`. Publishes `SubscriptionChanged`, `EntitlementChanged`.
Never touches deployment or infrastructure code — it publishes entitlements and
other contexts consult them.

### cms
Owns `ContentType`, `ContentItem`, `ContentVersion`, `Release`, `Taxonomy`.
Aggregates are version-immutable: publishing creates a new immutable version,
never mutates a published one. Publishes `ContentCreated`,
`ContentSubmitted`, `ContentApproved`, `ContentPublished`,
`ContentUnpublished`, `ContentRolledBack`.

### assets
Owns `Asset`, `AssetVersion`, `Rendition`, `Folder`. Uploads are validated,
scanned, quarantined until clean. Publishes `AssetUploaded`, `AssetScanned`,
`AssetPublished`.

### workflow
Generic engine: `WorkflowDefinition` (states, transitions, guards, actions),
`WorkflowInstance`, `Task`. Reused by CMS approval and, later, by any business
application. Publishes `WorkflowStarted`, `TaskAssigned`, `WorkflowCompleted`.

### deployment
Owns `Application`, `Release`, `Deployment`, `DeploymentArtifact`,
`EnvironmentVariable`, `SecretReference`, `Operation`. Publishes
`DeploymentStarted`, `DeploymentCompleted`, `DeploymentFailed`,
`DeploymentRolledBack`.

### infrastructure
Provider abstraction layer. Owns `StorageBinding`, `DatabaseInstance`,
`DatabaseBinding`, `KubernetesCluster`, `ProviderCredential`. Ports:
`StorageProvider`, `DatabaseProvider`, `KubernetesProvider`, `DnsProvider`,
`CertificateProvider`, `SecretStore`. Publishes `DatabaseProvisioned`,
`StorageProvisioned`, `CertificateIssued`.

### domains
Owns `Domain`, `DnsRecordInstruction`, `Certificate` (lifecycle state, not key
material). Verification uses a TXT challenge. Publishes `DomainVerified`,
`CertificateRenewed`, `CertificateFailed`.

### analytics
Owns `DataSource`, `Dataset`, `Metric`, `Dashboard`, `Report`, `Alert`.
Queries run against the analytical store, never against the transactional
database. Publishes `AlertTriggered`.

### integration
Owns `Integration` (SCM, cloud, SaaS), `WebhookEndpoint`, `WebhookDelivery`.
Publishes `WebhookDelivered`, `WebhookFailed`.

### notification
Owns `NotificationTemplate`, `Notification`, delivery attempts. Ports:
`EmailProvider`, `MessagingProvider`.

### audit
Owns `AuditRecord` — append-only, hash-chained. Consumes from every context;
publishes nothing.

### shared (kernel — not a context)
Cross-cutting, domain-free: identifiers, errors, API envelope, tenant context,
event envelope + outbox, pagination, quotas, rate limiting, secret wrappers,
observability helpers, feature flags. Contains **no** business rules.

## 3. Language discipline

| Term | Means exactly |
|------|---------------|
| Organization | The tenant. Billing and isolation root. |
| Workspace | Grouping of projects inside an organization (Phase 2 UI) |
| Project | A unit of work: a site, a portal, an application |
| Environment | `development` / `staging` / `production` (+ custom) inside a project |
| Application | Deployable workload inside an environment |
| Release | An immutable, versioned build of an application |
| Deployment | A release running in an environment |
| Content item | An instance of a content type |
| Content version | Immutable snapshot of a content item |
| Asset | A binary object plus metadata |
| Operation | A long-running, observable unit of asynchronous work |

Words that are banned because they collide across contexts: "user" (use
`principal` in cross-context code), "resource" (use the concrete type),
"environment variable" vs "environment" (always qualified).
