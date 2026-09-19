-- ============================================================================
-- V1_015 — A tenant directory background jobs can read, and platform events
-- ============================================================================
--
-- Two problems, both proven by OutboxRelayRlsIT against a real PostgreSQL 16:
--
--   1. Background jobs cannot enumerate tenants. org_organizations is in the strict
--      tenant table list and carries check (id = organization_id), so a session with
--      no tenant bound sees zero rows. The outbox relay and the webhook worker both
--      need to walk tenants one at a time, and neither has anywhere to get the list.
--   2. A platform-wide event has organization_id NULL, which can never equal a
--      tenant's id, so no tenant-scoped read can ever publish one.
--
-- The fix for (1) is a directory holding nothing but organization identifiers, with
-- no row level security. A second setting that widened reads on org_organizations
-- itself was rejected on purpose: those rows carry encryption_key_wrapped, and putting
-- wrapped tenant key material behind a flag any code path can set is a worse trade
-- than a table of ids.
--
-- The fix for (2) copies the shape already used for auth_roles and wf_definitions:
-- reads are widened to admit rows that belong to no tenant, WITH CHECK stays strict.

-- ---------------------------------------------------------------------------
-- Tenant directory
-- ---------------------------------------------------------------------------

create table plat_tenant_directory (
    organization_id uuid        primary key,
    created_at      timestamptz not null default now()
);

-- Deliberately not in the V1_013 tenant table list, so it carries no row level
-- security. It holds no tenant data - only which tenants exist - which is what a
-- background job needs in order to then bind each tenant and read its rows properly.
grant select, insert on plat_tenant_directory to hatis_app;

create index ix_plat_tenant_directory_created on plat_tenant_directory (created_at);

-- Existing tenants. org_organizations already has its policy in force from V1_013,
-- and FORCE applies to the table owner as well, so the backfill would otherwise see
-- nothing and silently migrate an empty directory. Dropping FORCE for the duration
-- lets the owner - the role running this migration - read the table, and it is put
-- straight back.
alter table org_organizations no force row level security;

insert into plat_tenant_directory (organization_id)
select organization_id from org_organizations
on conflict (organization_id) do nothing;

alter table org_organizations force row level security;

-- New tenants, kept in sync by the database rather than by remembering to write two
-- rows in the organization service. A trigger cannot be forgotten by a future code
-- path, and it fires for whoever inserts, so it needs no elevated privilege.
create or replace function plat_tenant_directory_sync() returns trigger as $$
begin
    insert into plat_tenant_directory (organization_id)
    values (new.organization_id)
    on conflict (organization_id) do nothing;
    return new;
end;
$$ language plpgsql;

create trigger trg_org_organizations_directory
    after insert on org_organizations
    for each row execute function plat_tenant_directory_sync();

-- Organizations are soft-deleted (deleted_at), so there is no delete trigger. A stale
-- directory entry costs a background job one empty query per sweep and nothing else.

-- ---------------------------------------------------------------------------
-- Platform-wide events become readable
-- ---------------------------------------------------------------------------

drop policy plat_outbox_tenant_isolation on plat_outbox;

-- USING admits rows with no organization so a relay can publish them; WITH CHECK does
-- not, so a tenant still cannot create an event that claims to belong to nobody. That
-- asymmetry is the whole point: reading a platform event is normal, smuggling one in
-- under a tenant's transaction is not.
create policy plat_outbox_tenant_or_platform on plat_outbox
    as permissive
    for all
    using (
        organization_id is null
        or organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
    )
    with check (
        organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
    );
