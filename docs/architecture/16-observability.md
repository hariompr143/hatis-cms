# 16 — Observability

Three signals, one correlation id, and a rule about what may never be logged.

## 16.1 Correlation

`CorrelationIdFilter` reads `X-Correlation-Id` or generates one, puts it in the MDC
and in the request metadata, and echoes it in the response header on every request.
It also appears in `ApiError.correlationId`, in every audit record and in every
domain event. A support ticket therefore resolves to a single trace without anyone
guessing a time window.

`ContextCopyingTaskDecorator` carries it into `@Async` work, and
`TenantContextHolder` carries the tenant, so a background job's logs are
attributable to the request that started it.

## 16.2 Metrics

Exposed at `/actuator/prometheus` with a `micrometer-registry-prometheus`
exporter. Naming is `hatis.<context>.<noun>` for platform metrics.

| Metric | Type | Purpose |
| --- | --- | --- |
| `hatis.auth.sign_in{result}` | counter | Success vs. failure; a spike in `failed` is an attack |
| `hatis.authz.decisions{decision}` | counter | Allow vs. deny volume |
| `hatis.audit.records` | counter | Audit writes |
| `hatis.audit.write_failures` | counter | **Alert on this.** A failing audit write means the platform is acting without a record |
| `hatis.outbox.pending` | gauge | Relay backlog |
| `hatis.outbox.relay_failures` | counter | Sink unreachable |
| `hatis.quota.exceeded{key}` | counter | Customers hitting plan limits — a billing signal as much as an ops one |
| `hatis.assets.scan{verdict}` | counter | `CLEAN` / `INFECTED` / `ERROR`; `ERROR` means the scanner is down and uploads are being quarantined |
| `hatis.deployment.rollout{outcome}` | counter | Rollout success vs. failure |
| `hatis.domains.certificate_expiry_days` | gauge | Per domain; alert below 21 |
| `hatis.storage.health` | gauge | Provider reachability |

Standard JVM, HTTP server and connection-pool metrics come from Micrometer and are
not re-implemented.

## 16.3 Logging

Structured key/value, one JSON object per line, at `INFO` by default. Every line
carries `correlationId`, and tenant-scoped lines carry `organizationId`.

Levels are used deliberately: `WARN` is a degraded-but-handled condition the
operator should see; `ERROR` is a request that failed. Neither is used for routine
flow, so an alert on `ERROR` rate is meaningful.

**Never logged:** passwords, password hashes, tokens, API key plaintext, MFA
secrets, private keys, certificate material, full signed URLs, request bodies of
authentication endpoints, and provider SDK exception messages.

## 16.4 Tracing

OpenTelemetry via `micrometer-tracing-bridge-otel`, exported over OTLP. Spans cover
HTTP requests, database calls, and every outbound adapter call, so "the deploy took
four minutes" resolves to the specific cluster API call that was slow rather than
to a guess.

Sampling is parent-based with a configurable ratio; errors are always sampled.

## 16.5 Health

| Endpoint | Meaning | Used by |
| --- | --- | --- |
| `/internal/health/liveness` | The process is alive. No dependency checks — a database outage must not cause a restart storm. | liveness probe |
| `/internal/health/readiness` | Database, Redis, object storage and secret store reachable. | readiness probe, load balancer |

The distinction matters: a liveness probe that checks dependencies turns a
transient database blip into a full pod restart across the fleet.

## 16.6 Audit trail

`aud_audit_logs` is not an observability signal; it is a control. Every record
carries `previous_hash`, `record_hash` and a per-tenant `sequence`, forming a chain
that `aud_verify_chain(uuid)` can validate. `update` and `delete` are revoked from
`hatis_app`, so the platform cannot rewrite its own history — verified by
`TenantIsolationIT`.

Audit writes use `PROPAGATION_REQUIRES_NEW` so a business rollback does not erase
the record that something was attempted, and a failing audit write increments
`hatis.audit.write_failures` rather than silently succeeding.

## 16.7 Dashboards and alerts

The four alerts that matter, in priority order:

1. `hatis.audit.write_failures` > 0 — the platform is acting without a record.
2. Tenant isolation violation logged (`tenant_mismatch`) — a bug or an attack.
3. `hatis.assets.scan{verdict="ERROR"}` sustained — uploads are being quarantined.
4. Certificate expiry < 21 days — a customer site is about to stop serving TLS.

Everything else is a dashboard, not a page.

## 16.8 What is not built

- **Per-tenant analytics is a product feature, not observability.** `hatis-analytics`
  covers customer-facing dashboards; this document covers the platform's own.
- **Log shipping.** Logs go to stdout and the customer's collector takes them. The
  platform does not embed a Loki or Elasticsearch client.
- **Continuous profiling.** Worth adding when a performance question needs it; not
  justified by a guess that it will.
