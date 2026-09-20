-- ============================================================================
-- V1_016 — A platform-wide outbox row can be published, not just read
-- ============================================================================
--
-- V1_015 widened USING on plat_outbox so a relay could see rows whose
-- organization_id is NULL, but left WITH CHECK strict. That combination is
-- actively worse than either extreme on its own:
--
--   * A relay bound to a tenant can now SELECT a platform-wide row.
--   * It still cannot UPDATE it, because WITH CHECK is evaluated against the new
--     row version on every update - including one that only touches
--     published_at - and organization_id = <tenant> is never true for a NULL.
--
-- So the relay would read the row, hand it to every sink, fail to record that it
-- had done so, and publish it again on the next sweep, forever. Downstream
-- consumers are idempotent by eventId, so it would not corrupt anything; it
-- would simply never drain, and the outbox backlog alarm would fire permanently.
--
-- The alternative - narrowing USING back and leaving platform-wide events
-- unreadable - was rejected because it makes them unpublishable instead of
-- infinitely republished, which is the same dead end with a quieter failure.
--
-- The fix is to stop expressing four different rules through one FOR ALL
-- policy. PostgreSQL evaluates policies per command, so each command gets the
-- rule that actually applies to it.

drop policy plat_outbox_tenant_or_platform on plat_outbox;

-- Reading: a tenant sees its own events and the platform-wide ones. This is the
-- rule V1_015 intended, unchanged.
create policy plat_outbox_select_tenant_or_platform on plat_outbox
    as permissive
    for select
    using (
        organization_id is null
        or organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
    );

-- Writing a new row: strictly the caller's own tenant. This is the guard that
-- matters, and it does not move. A tenant transaction cannot mint an event that
-- looks platform-issued, because such an event would be delivered to internal
-- consumers as though the platform itself had raised it.
create policy plat_outbox_insert_tenant_only on plat_outbox
    as permissive
    for insert
    with check (
        organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
    );

-- Recording delivery: a tenant may update its own rows, and the relay may update
-- a platform-wide one. WITH CHECK admits NULL so the update that sets
-- published_at on a platform event can commit.
--
-- The residual this accepts, stated plainly: a session able to UPDATE plat_outbox
-- could rewrite a platform-wide row it can already read, including its payload.
-- No tenant-facing code path issues an UPDATE against this table - the only
-- writers are EventPublisher, which inserts, and OutboxRelay, which sets
-- published_at and attempts - so the exposure is a future code path rather than
-- a present one. It is called out here so that the code path is the thing that
-- gets reviewed, rather than the policy being quietly widened later.
create policy plat_outbox_update_tenant_or_platform on plat_outbox
    as permissive
    for update
    using (
        organization_id is null
        or organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
    )
    with check (
        organization_id is null
        or organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
    );

-- Deleting: same shape as updating, so a retention job running without a tenant
-- bound can purge expired platform-wide rows as well as tenant ones.
create policy plat_outbox_delete_tenant_or_platform on plat_outbox
    as permissive
    for delete
    using (
        organization_id is null
        or organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
    );
