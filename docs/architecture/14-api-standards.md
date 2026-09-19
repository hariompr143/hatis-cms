# 14 — API standards

One set of rules for every endpoint, so that a client written against one context
predicts the behaviour of all of them.

## 14.1 Versioning and paths

- Prefix `/v1/`. A breaking change is `/v2/`, never an edit to `/v1/`.
- Resources are plural nouns: `/v1/assets`, `/v1/deployments`, `/v1/domains`.
- Sub-resources only where ownership is real: `/v1/assets/{id}/versions`.
- Actions that are not CRUD are verbs on the resource:
  `/v1/deployments/{id}/rollback`, `/v1/domains/{id}/verify`,
  `/v1/content/items/{id}/publish`. Modelling "publish" as `PATCH {status:
  "PUBLISHED"}` would hide a state transition with side effects inside a generic
  update.

## 14.2 Identifiers

UUIDs only, in path and body. Sequential ids are never exposed: they leak tenant
volume, invite enumeration, and make cross-tenant guessing cheap.

## 14.3 Authentication

`Authorization: Bearer <jwt>` for users, `Authorization: Bearer hatis_…` for API
keys and deployment tokens. Both are verified by the same filter and produce the
same `TenantContext`. There is no endpoint that accepts a tenant id from a header,
a query parameter or the body — the tenant comes from the token or not at all.

## 14.4 Status codes

| Code | Meaning |
| --- | --- |
| 200 | Returned a representation |
| 201 | Created; `Location` set |
| 202 | Accepted; work continues, `Location` points at the operation |
| 204 | Done, no body (deletes, publishes with no response body) |
| 400 | Malformed request or failed bean validation |
| 401 | No usable credentials |
| 403 | Authenticated, not permitted |
| 404 | Not found **within this tenant** — a resource that exists in another tenant is also 404, so existence is not disclosed across tenants |
| 409 | State conflict, duplicate, quota exceeded, idempotency conflict |
| 422 | Understood, but a business rule or policy refused it |
| 429 | Rate limited |
| 503 | A dependency is unavailable |

## 14.5 Error body

Every error, from any layer, has the same shape:

```json
{
  "code": "quota_exceeded",
  "message": "Quota exceeded for content_items",
  "correlationId": "0af7651916cd43dd8448eb211c80319c",
  "timestamp": "2026-09-16T10:12:33.482Z",
  "details": { "limitKey": "content_items", "limit": "2000", "requested": "2001" }
}
```

`code` is a stable machine-readable enum (`validation_failed`, `tenant_mismatch`,
`quota_exceeded`, `entitlement_missing`, `idempotency_conflict`, …). `message` is
safe to show a user. Stack traces, SQL, internal class names and dependency error
text never appear in a response.

`correlationId` is echoed in the `X-Correlation-Id` response header on every
request, success or failure, so a support conversation can be resolved against
logs without the customer guessing a time window.

## 14.6 Pagination

```
GET /v1/content/items?projectId=…&page=0&size=25
```

```json
{ "items": [ … ], "page": 0, "size": 25, "totalElements": 412, "totalPages": 17 }
```

`size` defaults to 25 and is capped at 200. Offset pagination is used because the
console needs page numbers; a cursor variant will be added for export endpoints
that page deeply enough for offset to hurt.

## 14.7 Long-running operations

Anything that outlives a request returns `202 Accepted` with a body and a
`Location`:

```json
{ "deploymentId": "…", "operationId": "…", "applicationSlug": "web", "releaseVersion": "1.4.2" }
```

```
GET /v1/operations/{operationId}
{ "id": "…", "state": "RUNNING", "progress": 40, "kind": "deployment.rollout",
  "errorCode": null, "errorMessage": null, "startedAt": "…", "finishedAt": null }
```

`state` is one of `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED`. The
operation row is written in the same transaction as the request, so a client that
received a `202` can always find out what happened — including when the worker
died mid-rollout.

## 14.8 Idempotency

Mutating calls accept `Idempotency-Key`. Repeating a key returns the stored
response; repeating a key with a different body returns
`409 idempotency_conflict`. Required on deployment calls, where a retried pipeline
step would otherwise deploy twice.

## 14.9 Rate limiting

A Redis token bucket per principal per route group, with an in-memory
implementation for single-node installs. Limits come from the plan, so they are a
billing concept rather than a hardcoded constant. Responses carry
`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.

## 14.10 Content types

`application/json` for everything except upload (`multipart/form-data`) and asset
delivery (a redirect to a signed URL). The API never streams asset bytes through
itself.

## 14.11 OpenAPI

`/v3/api-docs` is generated from the code — annotations on controllers and DTOs —
so it cannot drift from the implementation. A documented field that the code does
not return is a build-time inconsistency, not a documentation bug.

## 14.12 Compatibility

- Adding a field to a response is not breaking; clients must ignore unknown fields.
- Removing or renaming a field, changing a type, tightening validation, or changing
  a status code **is** breaking and requires a new major version.
- Deprecation is signalled with `Deprecation` and `Sunset` headers for at least one
  minor release before removal.
