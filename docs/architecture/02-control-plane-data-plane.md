# 02 — Control Plane and Data Plane

The single most important structural rule in HATIS-CMS: **the control plane
never needs unrestricted access to customer data, and customer data never needs
the control plane to be reachable.**

## 1. Control plane

Owns *facts about the customer*, not the customer's content.

| Responsibility | Context |
|----------------|---------|
| Tenants, organizations, workspaces, projects, environments | `organization` |
| Users, credentials, MFA, API keys, service accounts | `identity` |
| Roles, permissions, policy evaluation | `authorization` |
| Plans, subscriptions, entitlements, usage, invoices | `billing` |
| Domains, DNS verification, TLS certificates | `domains` |
| Infrastructure configuration and provider credentials | `infrastructure` |
| Deployment definitions, releases, deployment history | `deployment` |
| Audit records | `audit` |
| Feature flags, platform configuration | `shared` |

Stored in the **platform database** (PostgreSQL). Contains identifiers,
configuration, status, quota counters, entitlements and audit records.

## 2. Data plane

Runs customer workloads and holds customer content.

| Responsibility | Context |
|----------------|---------|
| Content types, content items, versions, publishing | `cms` |
| Digital assets, renditions, delivery | `assets` |
| Workflow instances | `workflow` |
| Analytics ingestion and query | `analytics` |
| Customer applications and their data | `deployment` (runtime), customer databases |

Stored in tenant-scoped storage:

* **SaaS**: same PostgreSQL cluster, isolated by `organization_id` + row level
  security; objects in tenant-prefixed buckets/keys.
* **Dedicated cloud**: dedicated cluster, dedicated buckets.
* **Private enterprise**: customer's own PostgreSQL and object storage, inside
  the customer network. The control plane holds only a reference
  (`infrastructure_binding` row) and the encrypted connection metadata the
  customer chose to share.

## 3. The boundary contract

1. Control plane → data plane calls are **narrow and explicit**: "publish this
   deployment", "issue a certificate", "create this bucket". They carry
   identifiers and configuration, never bulk customer content.
2. Data plane → control plane calls are **outbound only** and authenticated with
   a short-lived workload identity (SPIFFE-style SVID or a Kubernetes
   `ServiceAccount` token exchanged at the control plane).
3. The control plane never holds long-lived credentials that grant read access
   to customer content. Where the platform must reach customer infrastructure,
   the credential is scoped (single bucket, single database, least privilege)
   and stored envelope-encrypted in the customer's secret store.
4. Bulk data movement (export, migration, backup) runs in the data plane.

## 4. Private enterprise topology

```
                    ┌──────────────────────────────┐
                    │      HATIS CONTROL PLANE      │
                    │  tenants · entitlements ·     │
                    │  audit · billing · policy     │
                    └──────────────┬───────────────┘
                                   ▲
                       outbound TLS (mTLS), pull based
                                   │
        ═══════════════════════════╪═══════════════════════  customer edge
                                   │
                    ┌──────────────┴───────────────┐
                    │      HATIS CONNECTOR          │
                    │  · polls for desired state    │
                    │  · reports observed state     │
                    │  · never accepts inbound      │
                    │  · data-egress policy engine  │
                    └───┬──────────┬───────────┬────┘
                        │          │           │
              ┌─────────▼──┐  ┌────▼─────┐  ┌──▼──────────┐
              │ Kubernetes │  │ Postgres │  │ Object store│
              │  (tenant)  │  │ (tenant) │  │  (tenant)   │
              └────────────┘  └──────────┘  └─────────────┘
```

The connector is a small, signed, customer-operated agent. It **pulls** desired
state over an outbound mTLS channel and **pushes** only what the customer's
egress policy allows (status, health, audit events, metrics summaries). Customer
content never traverses the channel unless the customer explicitly enables a
support session.

### Egress policy

The customer declares, in a file the connector signs and the control plane
records, which categories may leave:

```yaml
egress:
  status: allowed            # health, versions, operation results
  metrics: allowed           # counters and gauges, no values
  audit: allowed             # audit records for actions initiated via the console
  diagnostics: on-demand     # requires an approved, time-boxed support grant
  content: never             # CMS content, assets, databases
```

The control plane enforces the same policy on ingestion: a payload category the
customer disabled is rejected with `422 policy_violation`, so a misconfigured
connector cannot exfiltrate by accident.

## 5. Disconnected operation

The data plane is fully functional with no control plane connectivity: content
is served, deployments keep running, local authentication continues against
cached policy. The connector buffers state changes and reconciles when the link
returns. Entitlement checks use the last known signed entitlement document with
a grace period (`hatis.private.entitlement-grace-period`, default 14 days),
after which the platform degrades to read-only rather than failing closed on a
customer's own production site.

## 6. What runs where — decision table

| Capability | SaaS | Dedicated cloud | Private | Self-managed |
|------------|------|-----------------|---------|--------------|
| Identity, RBAC | platform | platform | platform | platform |
| CMS content store | platform DB | dedicated DB | customer DB | customer DB |
| Assets | platform S3 | dedicated bucket | customer bucket | customer bucket |
| Secrets | platform secret store | platform store | customer Vault/KMS | customer store |
| Kubernetes | platform cluster | dedicated cluster | customer cluster | customer cluster |
| Certificates | ACME via cert-manager | ACME | customer CA or ACME | customer CA |
| Audit sink | platform | platform + customer SIEM | customer SIEM | customer SIEM |
| Support access | time-boxed grant | time-boxed grant | explicit, audited | none by default |
