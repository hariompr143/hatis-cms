# 09 — Kubernetes Architecture

## 1. Cluster topology (SaaS)

```
namespace: hatis-system                platform control plane + workers
namespace: hatis-ingress               ingress controller, cert-manager
namespace: hatis-observability         Prometheus, Grafana, Loki, Tempo (or managed equivalents)
namespace: tenant-<orgId-short>        one namespace per tenant (data plane workloads)
```

Separating the platform from tenant workloads at the namespace level gives:

* distinct `ResourceQuota` and `LimitRange` per tenant;
* distinct `NetworkPolicy` (tenant workloads cannot reach the control plane
  except through the ingress route, and cannot reach each other at all);
* distinct `ServiceAccount` and RBAC, so a compromised tenant workload cannot
  read another tenant's secrets;
* per-tenant `PodSecurity` admission set to `restricted`.

## 2. Platform namespaces

`hatis-system` contains `hatis-platform` (API) and `hatis-worker` (background
roles). Both are `Deployment`s with:

```yaml
replicas: 2                     # ≥2 always; HPA on CPU + custom queue-depth metric
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  fsGroup: 10001
  seccompProfile: { type: RuntimeDefault }
containerSecurityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: ["ALL"] }
volumes: [ emptyDir for /tmp ]
topologySpreadConstraints: maxSkew 1, topologyKey topology.kubernetes.io/zone
podDisruptionBudget: minAvailable 1
resources:
  requests: { cpu: 500m,  memory: 1Gi }
  limits:   { cpu: "2",   memory: 2Gi }
```

## 3. Kubernetes access model

| Principal | Permissions |
|-----------|-------------|
| `hatis-platform` ServiceAccount | Namespaced Role in `hatis-system`: read config/secrets it owns |
| `hatis-deployer` ServiceAccount | ClusterRole limited to `apps/deployments`, `services`, `ingresses`, `configmaps`, `secrets`, `pods`, `pods/log`, `namespaces` — **no** RBAC, CRD, node or cluster-scoped verbs |
| Tenant workload ServiceAccount | `automountServiceToken: false` unless the workload needs platform APIs, in which case a bound, audience-scoped token is issued |

The platform talks to the Kubernetes API over HTTPS with the cluster CA pinned
(`infra_kubernetes_clusters.ca_bundle_ciphertext`). For customer clusters the
credential is either a short-lived token issued by the connector or a scoped
kubeconfig supplied by the customer; both are stored envelope-encrypted.

## 4. Network segmentation

```
public     ── ingress controller (TLS termination, WAF annotations)
application── ingress → hatis-system services (mTLS optional)
management ── separate ingress class, IP allowlist, VPN/private endpoint only
database   ── no ingress; private subnet / peered VPC only
infra      ── egress to cloud APIs only, no inbound
```

`NetworkPolicy` default-deny ingress in every tenant namespace, with explicit
allow rules for the ingress controller and the platform API. Databases are never
exposed through a Service of type `LoadBalancer` or `NodePort`.

## 5. TLS

* cert-manager issues ACME (Let's Encrypt / ZeroSSL) certificates into
  `hatis-ingress`, with DNS-01 for wildcard domains and HTTP-01 otherwise.
* The `domains` context owns the *lifecycle record*; cert-manager owns the
  *key material*. The platform stores a reference and status, never the key.
* Customer-managed certificates are accepted: the customer uploads a PEM pair,
  it is stored envelope-encrypted, and a `kubernetes.io/tls` secret is created in
  `hatis-ingress`. Expiry is monitored and alerted at 30/14/7 days.

## 6. Multi-tenancy of the data plane

| Mechanism | Purpose |
|-----------|---------|
| Namespace per tenant | Isolation boundary for RBAC, quotas, policies |
| `ResourceQuota` per namespace | Enforces the plan's CPU/memory/storage limits |
| `LimitRange` per namespace | Prevents one pod from consuming a whole node |
| `NetworkPolicy` default-deny | No east-west traffic between tenants |
| `PodSecurity` `restricted` | No privileged containers, no host paths |
| `PriorityClass` | Platform > paid tenants > trial tenants |
| Node pools / taints (enterprise) | Physical separation for Dedicated Cloud |

## 7. Node and cluster concerns

| Concern | Decision |
|---------|----------|
| Node autoscaling | Cluster autoscaler / Karpenter, min 2 nodes across 2 zones |
| Upgrades | Managed control plane; nodes roll with `maxUnavailable=1` and PDB protection |
| Etcd backup | Managed etcd snapshots, 15-minute cadence, 7-day retention (SaaS) |
| Admission control | `PodSecurity`, `ResourceQuota`, plus OPA/Gatekeeper or Kyverno for image-signature and registry-allowlist policy |
| Image policy | Only images from the platform registry or an allowlisted customer registry, verified by cosign signature where the customer enables it |
| Ingress | NGINX or Envoy Gateway; per-tenant host rules generated from `dom_domains` |

## 8. The operator (Phase 3+)

A `HatisPlatform` CRD is introduced once the deployment model is stable. The
operator reconciles: platform installation, version upgrades, certificates,
database bindings, storage bindings, health and drift. It is a *packaging and
lifecycle* tool, not a place for business logic — business logic stays in the
platform services so that SaaS and private editions run identical code.

```yaml
apiVersion: hatis.io/v1alpha1
kind: HatisPlatform
metadata: { name: acme, namespace: hatis-system }
spec:
  version: 1.4.0
  database:  { mode: customerManaged, bindingRef: acme-postgres }
  storage:   { mode: customerOwned,   bindingRef: acme-s3 }
  connector: { egressPolicy: { content: never } }
status:
  phase: Ready
  conditions: [ … ]
```
