# 08 — Deployment Architecture

## 1. Object model

```
Application  (in an environment)
   └── Release           immutable: image + digest + SBOM + scan status + git commit
         └── Deployment  the act of running a release in an environment
               └── DeploymentHistory   append-only state transitions
```

Releases are immutable. Deployments are never edited in place — a rollback
creates a **new** deployment pointing at an earlier release, so history is never
overwritten and audit remains honest.

## 2. Sources

| Source | Flow | Phase |
|--------|------|-------|
| External CI/CD (GitHub Actions, GitLab CI, Jenkins, Azure DevOps, Argo CD) | Customer builds → pushes image → calls `POST /v1/deployments` with a deployment token | **1** |
| Container registry | Platform pulls a tagged image and deploys it | **1** |
| GitHub / GitLab / Bitbucket repository | Platform clones, builds, scans, pushes, deploys | 2 |
| Uploaded package | Platform builds an image from an uploaded archive | 2 |

Phase 1 deliberately ships the *external pipeline* path first: it is the one
enterprises actually use, and it means the platform never has to become a build
farm to be useful.

## 3. Deployment lifecycle

```
   POST /v1/deployments
   Idempotency-Key: 7c1f…
            │
            ▼
   202 Accepted { operationId, deploymentId, status: PENDING }
            │
   ┌────────▼──────────────────────────────────────────────────────┐
   │ OperationExecutor (worker role)                               │
   │   1 validate        entitlement, quota, image policy, scope   │
   │   2 resolve         image digest (pin by digest, never tag)   │
   │   3 configure       env vars + secrets from the secret store  │
   │   4 apply           Kubernetes Deployment/Service/Ingress     │
   │   5 health          readiness gate, bounded by progressDeadline│
   │   6 traffic         switch service selector / ingress weight  │
   │   7 finalize        status=COMPLETED, audit, events, webhook  │
   └───────────────────────────────────────────────────────────────┘
            │
            ▼
   GET /v1/operations/{operationId}
   { state: RUNNING, progress: 60, deploymentId, state: "health" }
```

Operation states: `PENDING → RUNNING → (SUCCESS | FAILED | CANCELLED)`.
Every transition appends to `dep_deployment_history` and `plat_operations`, so a
customer can see *why* a deployment failed, not just that it did.

### Strategies

| Strategy | Behaviour | Phase |
|----------|-----------|-------|
| `ROLLING` | Kubernetes rolling update, `maxUnavailable=0`, `maxSurge=1` | 1 |
| `RECREATE` | Stop then start (for workloads that cannot run two versions) | 1 |
| `BLUE_GREEN` | Second deployment + service selector switch, instant rollback | 2 |
| `CANARY` | Ingress weight ramp with automatic abort on error-rate regression | 2 |

### Health gates

A deployment is `COMPLETED` only when the new pods report `Ready` and the
configured HTTP readiness probe returns 2xx for `N` consecutive checks inside
`progressDeadlineSeconds`. Otherwise the deployment is marked `FAILED`, the
previous ReplicaSet is left serving traffic, and `DeploymentFailed` is published
with the pod events and the last 200 log lines attached to the operation.

### Rollback

```
POST /v1/deployments/{id}/rollback     → new deployment against a prior release
```

Rollback is a normal deployment: it re-runs health gates, writes history and
publishes events. There is no special "undo" path that could diverge from
reality.

## 4. Configuration and secrets

* **Environment variables** are stored plaintext in `dep_config_entries` and
  rendered into the workload's `envFrom`.
* **Secrets** are stored envelope-encrypted (`ciphertext` + `dek_id`) and
  materialised into a Kubernetes `Secret` at apply time in the tenant namespace.
  They are never returned by `GET /v1/environments/{id}/config`; the response
  carries `{ "key": "STRIPE_KEY", "secret": true, "set": true, "updatedAt": … }`.
* Secrets are per `(environment, application)`; production secrets require
  `environment:production:write`.
* Rotation replaces the value and triggers a rollout; the previous value is
  retained for the configured grace period so a rollback still works.

## 5. Logs

`GET /v1/deployments/{id}/logs?tail=500&since=10m` streams pod logs through the
Kubernetes API with an explicit byte cap and a per-request timeout. Log content
is classified `CONFIDENTIAL`: it is never written to the platform's own logs and
is redacted against the secret-value pattern list before returning.

## 6. Failure handling

| Failure | Behaviour |
|---------|-----------|
| Image not pullable | `FAILED` with `IMAGE_PULL_FAILED`; no rollout started |
| Readiness never achieved | `FAILED`, previous version keeps serving, pod events attached |
| Kubernetes API unreachable | `FAILED` after bounded retries with jitter; never retried for `delete` verbs |
| Quota exceeded | Rejected up front with `409 quota_exceeded` before any cluster mutation |
| Duplicate request | Idempotency key returns the original operation, no second rollout |
| Worker crash mid-operation | The operation row survives; a reaper resumes or fails it after `lease_expires_at` |

## 7. Zero-downtime requirements on the workload

The platform enforces, by default, the settings that make zero-downtime
possible, and warns when a workload overrides them:

```yaml
strategy:            { type: RollingUpdate, rollingUpdate: { maxUnavailable: 0, maxSurge: 1 } }
terminationGracePeriodSeconds: 30
lifecycle:           { preStop: { exec: { command: ["/bin/sh","-c","sleep 5"] } } }
readinessProbe:      { httpGet: { path: /healthz/ready }, periodSeconds: 5, failureThreshold: 3 }
livenessProbe:       { httpGet: { path: /healthz/live },  periodSeconds: 10, failureThreshold: 6 }
resources:           { requests == limits for CPU (Guaranteed QoS on the data plane) }
podDisruptionBudget: { minAvailable: 1 }
topologySpreadConstraints: across zones
```

## 8. Quotas enforced before any cluster mutation

`projects`, `environments`, `deployments`, `cpu_milli`, `memory_mib`,
`storage_gib`, `bandwidth_gib`, `builds_per_month`. Enforced server side by
`QuotaService`, sourced from entitlements — never from the client.
