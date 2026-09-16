# 06 — Tenant Isolation Strategy

Cross-tenant access is the highest-severity failure the platform can have. It is
defended in **four independent layers**, and each layer is covered by an
automated test that fails the build.

```
 request ──▶ [1] authenticated tenant resolution
          ──▶ [2] authorization on the resource scope
          ──▶ [3] every query carries organization_id
          ──▶ [4] PostgreSQL row level security (backstop)
```

## 1. Layer 1 — Tenant resolution

`TenantContext` holds the resolved `organizationId`, `principalId`, `correlationId`
and the active `scope`. It is populated by `TenantResolutionFilter` **only** from
verified material:

| Source | Trust |
|--------|-------|
| `org` claim of a validated JWT | Verified by signature against published keys |
| API key → `idp_api_keys.organization_id` | Looked up server side; the key itself carries no tenant claim the client controls |
| Service account token | Same |

A tenant id from a header, query parameter, request body or path segment is
**never** trusted. Path segments like `/v1/organizations/{orgId}/...` are
*verified* against the resolved context and rejected with `403 tenant_mismatch`
on disagreement.

`TenantContext` is cleared in a `finally` block and propagated to `@Async`
executors through a `TaskDecorator`, so a pooled thread can never inherit a
previous request's tenant.

## 2. Layer 2 — Authorization on the resource scope

`AuthorizationService.require(permission, ResourceScope.of(projectId))` walks the
hierarchy:

```
Organization → Workspace → Project → Environment → Resource
```

A binding at a higher scope grants the permission at every lower scope. A deny
binding at a lower scope wins. Production environments additionally require the
binding to be unexpired and the actor to hold `environment:production:write`.

## 3. Layer 3 — Explicit scoping in every query

Every tenant-scoped repository method takes `organizationId` as its **first**
parameter. There are no `findById(UUID)` methods on tenant-scoped repositories —
the omission is deliberate so that a forgotten parameter does not compile:

```java
public interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {

    Optional<ContentItem> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<ContentItem> findByOrganizationIdAndProjectId(UUID organizationId, UUID projectId, Pageable page);
}
```

ArchUnit enforces it:

```java
methods().that().areDeclaredInClassesThat().resideInAPackage("..adapter.persistence..")
    .and().haveNameMatching("find.*|read.*|load.*")
    .should().haveRawParameterTypes(anyOf(startsWith("java.util.UUID")))  // first arg is the tenant id
```

## 4. Layer 4 — PostgreSQL row level security

Even if a query is written wrong, the database refuses to return another
tenant's rows. Policies are created for every tenant-scoped table in
`V1_015__row_level_security.sql`:

```sql
alter table cms_content_items enable row level security;
alter table cms_content_items force row level security;

create policy cms_content_items_tenant_isolation on cms_content_items
    using (organization_id = current_setting('hatis.organization_id', true)::uuid)
    with check (organization_id = current_setting('hatis.organization_id', true)::uuid);
```

The session variable is set **transaction-scoped** at the start of every
tenant-scoped transaction by `TenantRlsAspect`:

```java
@Around("@annotation(tenantTransactional)")
public Object applyTenant(ProceedingJoinPoint pjp, TenantTransactional tenantTransactional) {
    return transactionTemplate.execute(status -> {
        jdbcTemplate.execute(
            "select set_config('hatis.organization_id', ?, true)");  // true = local to transaction
        return pjp.proceed();
    });
}
```

`force row level security` is on, so it applies to the table owner too. The
application role `hatis_app` has `BYPASSRLS` **off**. Platform-operator queries
run as `hatis_platform_admin` in a separate, audited path.

`current_setting(..., true)` returns `null` when unset, and `organization_id =
null` is never true, so an unset context returns **zero rows** rather than all
rows. Failing closed is the design.

## 5. Isolation modes and datasource routing

| Mode | Mechanism |
|------|-----------|
| `SHARED_SCHEMA` | RLS + explicit scoping (default) |
| `DEDICATED_SCHEMA` | `search_path` per tenant + RLS |
| `DEDICATED_DATABASE` | `TenantDataSourceResolver` selects a per-tenant datasource; credentials come from the secret store |
| `CUSTOMER_MANAGED` | Same resolver, connection terminates in the customer network via the connector |

The domain and application layers are identical in all modes; only the
`DataSource` differs. This is what lets one codebase serve SaaS and private
deployments.

## 6. Object storage isolation

* Tenant-prefixed keys: `org/{organizationId}/project/{projectId}/asset/{assetId}/{version}`.
* Bucket policies deny cross-prefix reads.
* Access is only through **presigned URLs** minted by `StorageProvider` with a
  short TTL (default 5 minutes, max 15) and an object-scoped signature.
* Public buckets are never used for `CONFIDENTIAL`/`RESTRICTED` assets; public
  delivery uses a separate CDN-backed bucket containing only published renditions.

## 7. Isolation tests

`TenantIsolationIT` runs for every tenant-scoped context and asserts:

```java
@Test
void tenantACannotReadTenantBContent() {
    var orgA = organizations.create("Acme");
    var orgB = organizations.create("Globex");
    var itemA = content.create(orgA.id(), projectA, "welcome", body);

    asUserOf(orgB);

    assertThatThrownBy(() -> content.get(itemA.id()))
        .isInstanceOf(ContentNotFoundException.class);   // not found, never "forbidden with data"
}

@Test
void rlsBlocksUnscopedNativeQuery() {
    asUserOf(orgB);
    var rows = jdbcTemplate.queryForList("select * from cms_content_items");
    assertThat(rows).isEmpty();                          // RLS backstop holds
}

@Test
void tenantContextDoesNotLeakAcrossThreads() { ... }
```

Cross-tenant attempts are written to `aud_audit_logs` with
`result = 'DENIED'` and raise a `security.tenant_isolation_violation` metric that
pages the operator.

## 8. Threats explicitly considered

| Threat | Mitigation |
|--------|------------|
| IDOR by guessing UUIDs | UUIDv4 (122 bits), plus scope check, plus RLS |
| JWT `org` claim tampering | RS256 signature verification; `iss`/`aud` pinned; short TTL |
| API key from tenant A used on tenant B path | Key → org lookup; path org must match |
| Async job inheriting a stale tenant | `TaskDecorator` copies and clears `TenantContext` |
| Scheduled job with no tenant | Runs as platform principal; tenant loops are explicit and audited |
| Cache key collision | Every cache key is prefixed `org/{organizationId}/` |
| Search index bleed | Index documents carry `organization_id` and every query filters on it |
| Log/audit bleed | Tenant id is a structured log field; export endpoints are org-scoped |
