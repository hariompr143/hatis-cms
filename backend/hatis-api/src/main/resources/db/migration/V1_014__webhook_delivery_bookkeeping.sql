-- ============================================================================
-- V1_014 — Outbound webhook delivery bookkeeping
-- ============================================================================
--
-- int_webhook_deliveries was created in V1_011 as an append-only log. That was
-- wrong about the thing it records: a delivery is mutable. attempts increases
-- on every try, status moves PENDING -> DELIVERED/FAILED/SKIPPED, next_attempt_at
-- is rescheduled on retry and last_error is overwritten. It needs the same
-- bookkeeping columns every other tenant-owned table has, so that the row maps
-- to TenantScopedEntity and is optimistically locked like the rest of them.
--
-- updated_at is not decoration here. Retries are spread over hours, so "when did
-- this delivery last change state" is the only way to tell a delivery that is
-- being actively retried from one a worker picked up and then died on.

alter table int_webhook_deliveries
    add column updated_at timestamptz not null default now(),
    add column version    bigint      not null default 0;

-- Matches the naming the V1_011 loop uses for every other mutable table.
create trigger trg_int_webhook_deliveries_touch
    before update on int_webhook_deliveries
    for each row execute function plat_touch_updated_at();
