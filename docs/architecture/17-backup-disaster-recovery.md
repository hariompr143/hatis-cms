# 17 — Backup and disaster recovery

What is recoverable, from what, and how it is proven. A backup that has never been
restored is a hypothesis.

## 17.1 What must survive

| Data | Store | Recovery objective |
| --- | --- | --- |
| Platform metadata, content, configuration | PostgreSQL | RPO 5 min, RTO 1 h |
| Assets and renditions | Object storage | RPO 15 min, RTO 4 h |
| Secrets and signing keys | Secret manager | RPO 0 (replicated by the provider), RTO 30 min |
| Audit trail | PostgreSQL, append-only | Same as platform metadata; a gap is a compliance incident |
| Tenant databases (enterprise/private) | Customer-owned or provisioned PostgreSQL | RPO 5 min, RTO 1 h |

Content in PostgreSQL and bytes in object storage are backed up separately, which
means a restore can put them out of step. §17.5 is how that is detected.

## 17.2 PostgreSQL

**Continuous.** WAL archiving to object storage with a 5-minute maximum lag, plus a
full base backup daily. Point-in-time recovery to any moment inside the retention
window.

**Where.** A different failure domain from the primary — another availability zone
at minimum, another region for the `private` plan.

**Who owns it.** For `database.external: true` installs, the customer's own backup
regime applies and the platform records what it was told. For provisioned
databases, the platform owns the schedule and exposes `latestBackup` through
`DatabaseProvider`.

**Verified by.** A scheduled restore into a scratch instance, followed by
`aud_verify_chain` on every tenant and a row-count comparison against the source.
A restore that is not run does not count as a backup.

## 17.3 Object storage

Versioning enabled on the asset bucket, cross-region replication for `enterprise`
and `private`, lifecycle rules moving previous versions to infrequent-access
storage after 30 days. Deletion is soft: `AssetService.delete` marks the row and a
scheduled purge removes objects past retention, so an accidental delete inside the
grace period is recoverable.

Object lock (compliance mode) is available for tenants with a regulatory retention
requirement; it is opt-in because it makes deletion impossible by design.

## 17.4 Secrets

Signing keys and tenant KEKs live in the secret manager, which replicates them
itself. Losing the KEK makes every tenant DEK unreadable, which makes every stored
MFA secret and encrypted field unrecoverable — so KEK loss is treated as a
catastrophic event with its own runbook, not as "restore from backup".

Token signing key rotation is supported through `kid`: a new key is published, the
old one stays valid for the access-token TTL, and no user is signed out by a
rotation.

## 17.5 Consistency between stores

A content row can reference an asset whose object was restored to an earlier point.
`AssetService.verifyIntegrity` compares the stored SHA-256 against what object
storage reports, and a reconciliation job runs it across recently changed assets
after any restore. A mismatch is surfaced as `DEGRADED` rather than served.

## 17.6 Failure scenarios

| Scenario | Response |
| --- | --- |
| Pod crash | Kubernetes restarts it; no data loss — state is in PostgreSQL |
| Node failure | Pods reschedule; `podDisruptionBudget.minAvailable: 2` keeps capacity |
| Availability zone loss | Topology spread across zones; traffic shifts; no failover decision needed |
| Region loss | DNS failover to the standby region; PostgreSQL promoted from the replica; object storage served from the replication target |
| PostgreSQL corruption | PITR to the last good moment; the audit chain is verified before the platform accepts traffic |
| Object storage region loss | Replication target promoted; asset rows still resolve by key |
| Ransomware / malicious deletion | Soft delete plus versioning means recovery does not depend on the attacker being stopped first |
| Worker fleet down | Outbox rows accumulate and are relayed when workers return; no event is lost |
| Secret manager unavailable | Platform fails closed — sign-in and secret reads fail rather than falling back to plaintext |

## 17.7 Recovery procedure

1. Declare the incident and pick the recovery point.
2. Restore PostgreSQL to that point into a fresh instance.
3. Verify: `aud_verify_chain` for every tenant, migration version matches the
   running image, row counts within tolerance.
4. Point the platform at the restored instance; keep the old one for forensics.
5. Run asset integrity reconciliation; mark mismatches `DEGRADED`.
6. Re-enable traffic; watch `hatis.audit.write_failures` and sign-in error rate.
7. Publish what was lost, to the minute, per tenant.

Step 3 is not optional. Restoring to a point where the audit chain is broken means
the platform cannot prove what it did, which is worse than the outage.

## 17.8 Testing

Quarterly, on a copy of production:

- full restore from the base backup plus WAL to an arbitrary timestamp;
- audit chain verification across all tenants;
- asset integrity reconciliation;
- a deployment executed against the restored control plane;
- timed, with the RPO/RTO above as the pass criterion.

The result is recorded. An untested recovery plan is documentation, not a
capability.
