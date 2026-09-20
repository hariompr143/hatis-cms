# 15 — Security architecture

Security is the first priority in the platform's ordering, ahead of correctness and
everything else, because a multi-tenant SaaS that leaks one customer's data has
failed regardless of how well the rest of it works. This document states the
controls, where they are enforced, and how each is tested.

## 15.1 Threat model summary

| Threat | Primary control | Where |
| --- | --- | --- |
| Cross-tenant data read | Explicit `organization_id` predicate + PostgreSQL RLS | repositories, `V1_013` |
| Cross-tenant write / ownership reassignment | RLS `WITH CHECK` | `V1_013` |
| Credential stuffing | BCrypt cost 12, lockout, enumeration-resistant responses, rate limiting | `AuthenticationService` |
| Session hijack | 15-minute RS256 access tokens, rotating hashed refresh tokens with family reuse detection | `TokenService` |
| Token replay after MFA reset | `sid` claim bound to the session, all sessions revoked on second-factor change | `MfaService` |
| Stored XSS through the CMS | Server-side HTML sanitising at write time | `RichTextSanitizer` |
| Malicious upload | Inline ClamAV scan before an asset is deliverable | `AssetService` |
| Domain takeover | DNS ownership challenge before any certificate or routing | `DomainService` |
| Secret exposure | No secret in Git, config or logs; `Secret` type whose `toString()` is `[REDACTED]` | `SecretStore`, `Secret` |
| Privilege escalation in a tenant workload | Non-root, read-only rootfs, all capabilities dropped, no service-account token | `KubernetesDeploymentProvider` |
| Supply-chain | Image pinned by digest, scan must pass before deploy, SBOM recorded | `Release` |
| Audit tampering | Hash-chained, append-only log; `revoke update, delete` from the app role | `V1_012` |

## 15.2 Defence in depth for tenant isolation

Three independent layers, because any one of them can be defeated by a bug:

1. **Type system.** `TenantScopedEntity` makes the owning organization a
   constructor argument and immutable. An ArchUnit rule requires every
   tenant-owned aggregate to extend it.
2. **Query.** Every repository finder takes `organizationId` explicitly. There is
   no `findById` on a tenant-scoped repository.
3. **Database.** Row level security with `force row level security`, evaluated
   against a transaction-local setting that is unset unless the platform bound it.
   An unbound transaction sees zero rows, not all rows.

`TenantIsolationIT` proves layer 3 against a real PostgreSQL 16 instance running as
`hatis_app`, including a direct primary-key lookup across tenants and an attempted
ownership reassignment.

## 15.3 Authorization

Permissions are rows, not an enum, so adding a capability is a migration rather than
a coordinated deploy. Evaluation order in `AuthorizationService`:

1. bindings covering the requested scope or any ancestor scope are collected;
2. an explicit `DENY` at a covering scope wins;
3. otherwise allow if any covering `ALLOW` role grants the permission.

Organization scope covers everything beneath it, which is what makes "may deploy
everywhere except production" expressible without a new role.

`@PreAuthorize("hasPermission(…)")` on controllers is a fast-fail convenience. The
authoritative check is in the application service, which is also reached by
background jobs where no HTTP authentication object exists.

## 15.4 Secrets

- Nothing secret in Git, in `application.yml`, or in a Helm values file. The chart
  reads from an existing Secret named by `existingSecret`; it never creates one.
- `Secret` is a distinct type: `toString()` returns `[REDACTED]`, it is not a bean
  property so Jackson never serialises it, and `destroy()` zeroes the array.
- `VaultSecretStore` and `AwsSecretsManagerSecretStore` read credentials from the
  ambient chain (IAM role, IRSA, Kubernetes service account). Neither accepts a
  credential in configuration.
- Failure is `DependencyUnavailable` and callers fail closed. There is no fallback
  from a real secret manager to plaintext, because a silent fallback is exactly the
  failure the abstraction exists to prevent.

## 15.5 Cryptography

| Purpose | Scheme |
| --- | --- |
| Passwords | BCrypt, cost 12 |
| Access tokens | RS256, 15-minute TTL, `iss`/`aud`/`kid` validated |
| Refresh tokens | 256-bit random, stored SHA-256, rotating, `family_id` reuse detection |
| API keys | `hatis_` + 32 random bytes, stored SHA-256, 12-char prefix shown |
| Tenant data | AES-256-GCM envelope: per-tenant DEK wrapped by a KEK in the secret store |
| MFA | RFC 6238 TOTP, SHA-1, 6 digits, 30 s, ±1 window, constant-time compare, per-step replay guard |
| Object storage | Server-side AES-256 |
| TLS in transit | Everywhere; ingress forces redirect, HSTS one year with preload |

The TOTP secret is encrypted with the tenant DEK before it touches the database and
is decrypted only for the microseconds needed to verify a code. There is no
"show my secret again" endpoint, because there cannot be one.

## 15.6 What never appears in a log or response

Passwords, hashes, tokens, API key plaintext, MFA secrets, private keys,
certificate material, full signed URLs, and provider SDK exception messages (which
routinely embed endpoints and request ids). Adapters log the exception class and
throw a message they wrote themselves.

## 15.7 Hardening that is not optional

The Helm chart and the deployment manifests both set: `runAsNonRoot`, a fixed
non-zero uid, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, all
capabilities dropped, `seccompProfile: RuntimeDefault`, and
`automountServiceAccountToken: false` on tenant workloads. A customer container is
assumed to be compromised eventually; these limit what that costs.

## 15.8 Verification

| Control | Test |
| --- | --- |
| RLS blocks cross-tenant read, insert, reassignment | `TenantIsolationIT` |
| App role has no `BYPASSRLS`, no write on history tables | `TenantIsolationIT` |
| Catalogue rows visible and read-only per tenant | `TenantIsolationIT` |
| Layer and context boundaries | `HexagonalArchitectureTest` |
| Tenant-owned aggregates extend `TenantScopedEntity` | `HexagonalArchitectureTest` |
| Script, event-handler, `javascript:` payloads neutralised | `RichTextSanitizerTest` |
| Schema validation rejects unknown fields and bad types | `ContentBodyValidatorTest` |
| Keys cannot escape the tenant prefix; traversal refused | `StorageKeysTest` |
| Executable content types refused; unscanned asset not deliverable | `AssetTest` |
| `latest` and unpinned images not deployable; failed scan blocks deploy | `ReleaseTest` |

## 15.9 Known limits, stated plainly

- **No WAF in front of the API.** The ingress is nginx with TLS and HSTS; a
  customer wanting a WAF puts one in front. Documented, not implemented.
- **Rate limiting is per instance for the in-memory limiter.** The Redis limiter is
  the multi-replica answer; the in-memory one is for single-node installs and is
  documented as such.
- **Malware scanning covers uploads, not content bodies.** A rich-text field is
  sanitised, not scanned for embedded payloads beyond HTML.
- **Private deployment isolation controls (Phase 2)** — customer VPC, private
  link, dedicated control plane — are constrained for but not built.
