# 13 — Event architecture

The platform publishes domain events and guarantees they are not lost. This
document describes the mechanism, the ordering and delivery guarantees, and what a
consumer may assume.

## 13.1 The problem being solved

Publishing an event after a transaction commits loses the event when the process
dies between the commit and the send. Publishing inside the transaction and
rolling back on send failure couples the business transaction to the broker's
availability. Both are unacceptable for events that drive webhooks, billing
metering and audit.

## 13.2 Transactional outbox

```
service ──► @Transactional ──► business row + plat_outbox row   (one transaction)
                                        │
OutboxRelay (worker) ──────────────────┘──► EventSink ──► webhook / Kafka
                                        │
                              mark published, or retry
```

`EventPublisher.publish()` writes to `plat_outbox` with
`Propagation.MANDATORY` — publishing outside a transaction is a programming error
that fails immediately rather than silently dropping the event.

`OutboxRelay` runs on the worker role, claims pending rows with a lease
(`lease_expires_at`), sends them and marks them published. A worker that dies
mid-send leaves the lease to expire and the row is retried.

**Guarantee: at-least-once.** A consumer must be idempotent. Every event carries an
`eventId` that is stable across retries.

## 13.3 Event shape

```java
public record PlatformEvent(
    UUID eventId, String eventType, int eventVersion, UUID organizationId,
    String resourceType, UUID resourceId, String correlationId,
    Instant occurredAt, Map<String, Object> data)
```

- `eventType` is `context.entity.verb`: `cms.content.published`, `asset.uploaded`,
  `deployment.finished`, `domain.provisioned`.
- `eventVersion` starts at 1 and increments when `data` changes shape. A breaking
  change to a published event type gets a new version, not a silent edit.
- `organizationId` is always present, so a consumer can partition per tenant.
- `data` never contains secret material. An event says a token was rotated; it does
  not carry the token.

## 13.4 Ordering

**No global ordering is promised.** Within one aggregate, events are relayed in
`occurred_at` order because the relay claims rows in sequence per partition, but a
consumer must not depend on it across aggregates or across workers.

Where order genuinely matters — a deployment finishing before a rollback starts —
the platform does not rely on events. The state machine in the aggregate enforces
it, and the event only reports what already happened.

## 13.5 Sinks

`EventSink` has two implementations:

- **`LocalEventSink`** — in-process dispatch, used when `hatis.event.transport` is
  `outbox`. Webhook delivery happens through it.
- **`KafkaEventSink`** — publishes to a topic per context when
  `hatis.event.transport` is `kafka`. Kafka is opt-in: a private installation
  should not need a Kafka cluster to run the platform.

## 13.6 Webhooks

`int_webhook_endpoints` holds the registrations; `int_webhook_deliveries` holds
every attempt.

- **Signing.** `Hatis-Signature: t=<unix>,v1=<hex>` where the MAC is HMAC-SHA256
  over `t.rawBody`. The timestamp is inside the signed payload so a captured
  request cannot be replayed indefinitely; receivers are told to reject a
  timestamp older than five minutes.
- **Retries.** 1m, 5m, 30m, 2h, 8h, then the endpoint is disabled and the tenant is
  notified. An endpoint that fails forever must not consume the relay's capacity.
- **No SSRF.** Endpoint URLs may not resolve to private address ranges. The check
  happens at registration and at delivery, because a DNS name that resolved
  publicly at registration can resolve internally later.
- **Timeouts.** Ten seconds, and the response body is not read beyond 4 KiB.

## 13.7 What events are not for

- **Not a command bus.** Nothing in the platform changes state because an event
  arrived. Events report; services decide.
- **Not an audit log.** The audit trail is hash-chained, append-only and written in
  the same transaction as the action. Events can be replayed, reordered or dropped
  by a consumer; an audit record cannot.
- **Not a read model.** There is no CQRS projection. The delivery API reads the
  published version directly, which is simpler and cannot drift.

## 13.8 Failure behaviour

| Failure | Behaviour |
| --- | --- |
| Relay crashes mid-send | Lease expires, row retried |
| Sink unreachable | Row stays pending, retried with backoff |
| Webhook endpoint 5xx | Delivery recorded, retried per the schedule above |
| Webhook endpoint 4xx | Delivery recorded, not retried — the request was understood and refused |
| Outbox table growing | Published rows older than the retention window are purged by a scheduled job |
| Event schema changed | `eventVersion` increments; consumers pin a version |
