# 07 — Authentication and Authorization

## 1. Authentication

### Password sign-in

```
POST /v1/auth/sign-in            { email, password, captchaToken? }
  ├─ unknown email            → 401 invalid_credentials   (same shape, same latency)
  ├─ wrong password           → 401 invalid_credentials
  ├─ account locked           → 423 account_locked
  ├─ MFA enrolled             → 200 { mfaToken, challenge: "totp" }
  └─ success                  → 200 { accessToken, refreshToken, expiresIn }
```

* Passwords hashed with **bcrypt**, cost 12, per-user salt. Never logged, never
  returned, never included in an export.
* `failed_sign_in_count` and `locked_until` implement lockout (5 failures → 15
  minute lock, exponential on repetition).
* User enumeration is prevented: unknown emails return the same status, error
  code and *response time* as a wrong password (a constant-time comparison is
  performed against a dummy hash).
* Password policy: NIST SP 800-63B — minimum 12 characters, no composition
  rules, checked against a breached-password list, no password hints.

### MFA (TOTP, RFC 6238)

* Enrolment returns a `otpauth://` URI + secret once; the secret is stored
  envelope-encrypted and never returned again.
* Verification window: ±1 step (30 s), with a replay guard so a code cannot be
  reused inside its window.
* 10 single-use recovery codes, bcrypt-hashed individually.
* Platform administrators and any role with `security:*` permissions **must**
  have MFA enrolled; enforced server side on role assignment, not just in the UI.

### Tokens

| Token | Type | TTL | Storage |
|-------|------|-----|---------|
| Access token | RS256 JWT | 15 min | Client memory / httpOnly cookie; never `localStorage` |
| Refresh token | opaque, 256-bit | 30 days | SHA-256 hash in `idp_refresh_tokens` |
| MFA token | short-lived JWT | 5 min | Client memory |
| API key | `hatis_` + 32 bytes base62 | Until revoked/expired | SHA-256 hash in `idp_api_keys`; shown **once** at creation |
| Workload identity (data plane) | exchanged SA token | 1 h | In-memory |

JWT claims:

```json
{
  "iss": "https://api.hatis.example",
  "sub": "01890c...",              // user id
  "aud": "hatis-platform",
  "org": "01890d...",              // organization id — authoritative tenant
  "sid": "01890e...",              // session id, for revocation
  "roles": ["ORG_ADMIN"],          // coarse roles only; fine-grained checks are server side
  "amr": ["pwd", "totp"],
  "jti": "…", "iat": …, "exp": …
}
```

Signing keys are RS256, held in the secret store, rotated on a schedule with
overlapping validity (`kid` selects the key). Private keys never appear in Git,
application configuration, logs or API responses.

**Refresh token rotation with reuse detection.** Every refresh issues a new
token and revokes the presented one, both linked by `family_id`. If a revoked
token from a family is presented again, the entire family is revoked and a
`security.token_reuse` event is raised — that is a stolen-token signature.

### API keys and service accounts

```
hatis_pk_live_9f3a…   (shown once)
```

* Stored as SHA-256 hash; only an 8-character prefix is kept for identification
  in the UI and audit records.
* Scoped (`scopes text[]`) — an API key cannot exceed the permissions of the
  service account it belongs to.
* Optional expiry; last-used timestamp; instant revocation.
* Rate limited per key.

### SSO (Phase 2)

OIDC and SAML 2.0 per organization, with JIT provisioning, attribute mapping and
SCIM 2.0 for user/group sync. The `IdentityProvider` port exists in Phase 1 so
the local credential path and the federated path share one user model.

## 2. Authorization

### Model

RBAC with hierarchical scopes and deny overrides — i.e. RBAC plus a thin policy
layer, not a full ABAC engine.

```
Permission  = <resource-type>:<action>          e.g. cms:content:publish
Role        = named set of permissions          (system or tenant-defined)
RoleBinding = (principal, role, scope)          scope = ORGANIZATION|PROJECT|ENVIRONMENT|RESOURCE
```

System roles:

| Role | Notable permissions |
|------|---------------------|
| Owner | Everything, including organization transfer and deletion |
| Organization Admin | Manage members, projects, integrations, billing view |
| Security Admin | Policies, audit, secrets, MFA enforcement, support grants |
| Billing Admin | Plans, subscriptions, invoices, payment methods |
| Developer | Deployments, environments, config, secrets, API keys |
| Editor | Content and assets, submit for approval, publish where allowed |
| Analyst | Dashboards, reports, datasets, read-only content |
| Viewer | Read-only within the bound scope |
| Custom | Tenant-defined subsets |

### Evaluation

```java
Decision evaluate(Principal principal, String permission, ResourceScope scope) {
    // 1. feature flag / entitlement gate
    // 2. collect bindings covering scope (own scope + all ancestors)
    // 3. explicit deny at the narrowest scope wins
    // 4. allow if any binding's role grants the permission
    // 5. production environments additionally require a non-expired binding
}
```

Decisions are cached per `(principal, permission, scope)` for 60 s and
invalidated on `RoleBindingChanged`.

### Enforcement points

1. **HTTP** — `@PreAuthorize("hasPermission('cms:content:publish', #projectId)")`
   resolved by a custom `PermissionEvaluator`; the project id is resolved to a
   scope before the check.
2. **Application service** — `authorization.require(...)` at the top of every
   mutating use case. This is the authoritative check; the HTTP annotation is a
   fast-fail convenience.
3. **Data** — RLS as the last line of defence (see [06](06-tenant-isolation.md)).

Frontend authorization is **advisory only**: the console hides what a user cannot
do, but the server re-checks on every request.

### Privileged access

| Rule | Implementation |
|------|----------------|
| Platform operators have no default data access | Operator role grants metadata access only; content access requires a support grant |
| Support access is explicit, time-boxed, audited | `support_grants` row with expiry (max 8 h), reason, approver; every action during the grant is audit-flagged |
| Break-glass is possible but loud | `PLATFORM_EMERGENCY` role requires two-person approval and raises a security event |
| Production changes need stronger authorization | `environment:production:write` + optional MFA re-authentication (`amr` freshness check) |

## 3. Session and revocation

* Sign-out revokes the refresh token family and adds the access token `jti` to a
  Redis denylist until its natural expiry.
* Password change, MFA change, role change or account disable revokes all
  sessions for that user.
* `sid` in the JWT lets an administrator terminate one session without killing
  all of them.

## 4. Abuse prevention

| Control | Value |
|---------|-------|
| Sign-in rate limit | 10 / minute / IP, 5 / minute / email |
| API rate limit | Per plan, per key, per IP (see [14](14-api-standards.md)) |
| Token endpoint rate limit | 30 / minute / principal |
| Webhook signature | HMAC-SHA256 with per-endpoint secret + timestamp anti-replay |
| CSRF | SameSite=strict cookies + `X-CSRF-Token` for cookie-authenticated state changes |
| Brute-force on MFA | 5 attempts per challenge, then re-authentication required |

## 5. Security events raised by this context

`suspicious_sign_in` · `repeated_failed_sign_in` · `impossible_travel` ·
`token_reuse` · `mfa_bypass_attempt` · `privilege_escalation` ·
`api_key_created` · `api_key_revoked` · `support_grant_opened`

Each becomes an `aud_audit_logs` row with `result` and, where configured, a
notification to the Security Admin.
