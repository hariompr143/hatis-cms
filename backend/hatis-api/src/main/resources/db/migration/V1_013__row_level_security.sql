-- ============================================================================
-- V1_013 — Row level security
--
-- The last isolation layer. Even a query written without its organization_id
-- predicate returns nothing for another tenant, because the policy compares
-- against a transaction-local setting that is unset unless the platform bound it.
--
-- current_setting('hatis.organization_id', true) returns NULL when unset, and
-- `organization_id = NULL` is never true — so an unbound transaction sees zero
-- tenant rows rather than all of them. Failing closed is the intent.
--
-- `force row level security` makes the policy apply to the table owner too.
-- The application role runs with BYPASSRLS off.
-- ============================================================================

do $$
declare
    t text;
    tenant_tables text[] := array[
        -- organization
        'org_organizations','org_workspaces','org_projects','org_environments','org_memberships','org_support_grants',
        -- identity
        'idp_service_accounts','idp_api_keys',
        -- authorization (auth_roles is catalogue-aware; see the second loop)
        'auth_role_bindings',
        -- billing
        'bill_subscriptions','bill_usage_records','bill_invoices','bill_payment_transactions',
        -- infrastructure
        'infra_storage_bindings','infra_database_instances','infra_database_bindings','infra_kubernetes_clusters','infra_secrets',
        -- domains
        'dom_domains','dom_certificates',
        -- assets
        'assets','asset_versions','asset_metadata','asset_renditions','asset_folders',
        -- workflow (wf_definitions is catalogue-aware; see the second loop)
        'wf_instances','wf_tasks','wf_history',
        -- cms
        'cms_content_types','cms_content_items','cms_content_versions','cms_releases','cms_taxonomy_terms',
        -- deployment
        'dep_applications','dep_releases','dep_deployments','dep_deployment_history','dep_config_entries','dep_deployment_tokens',
        -- integration
        'int_integrations','int_webhook_endpoints','int_webhook_deliveries',
        -- analytics
        'anl_data_sources','anl_datasets','anl_metrics','anl_dashboards','anl_reports','anl_alerts',
        -- notification
        'ntf_notifications',
        -- kernel
        'plat_operations','plat_outbox','plat_idempotency_keys','plat_usage_counters',
        -- audit
        'aud_audit_logs'
    ];
begin
    foreach t in array tenant_tables loop
        if to_regclass(t) is null then
            raise warning 'RLS skipped: table % does not exist', t;
            continue;
        end if;

        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);

        -- Reads and writes are both constrained: WITH CHECK stops a row being
        -- inserted or updated into another tenant, not just read from one.
        execute format($f$
            create policy %I on %I
                as permissive
                for all
                using (
                    organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
                )
                with check (
                    organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
                )
        $f$, t || '_tenant_isolation', t);
    end loop;
end $$;

-- ---------------------------------------------------------------------------
-- Catalogue-aware tenant tables.
--
-- auth_roles and wf_definitions hold rows that belong to no tenant: the system
-- roles shipped with the platform and the default workflow templates. Every tenant
-- must be able to read those, but must never be able to write them or to insert a
-- row that another tenant would then see. So reads are widened to include the
-- catalogue, while WITH CHECK stays strictly tenant-scoped.
--
-- The strict policy above would hide those rows entirely, which would mean a new
-- organization has no roles at all - a correct-looking schema that breaks at
-- runtime.
-- ---------------------------------------------------------------------------
do $$
declare t text;
begin
    foreach t in array array['auth_roles','wf_definitions'] loop
        if to_regclass(t) is null then
            raise warning 'RLS skipped: table % does not exist', t;
            continue;
        end if;

        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);

        execute format($f$
            create policy %I on %I
                as permissive
                for all
                using (
                    organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
                    or organization_id is null
                )
                with check (
                    organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
                )
        $f$, t || '_tenant_or_catalogue', t);
    end loop;
end $$;

-- Tables that are not tenant-scoped: users are platform-wide, and the catalogue
-- tables (plans, permissions) are shared reference data.
do $$
declare t text;
begin
    foreach t in array array['idp_users','idp_refresh_tokens','idp_mfa_enrolments','idp_sign_in_events',
                             'auth_permissions','bill_plans','bill_plan_limits','bill_entitlement_codes',
                             'plat_feature_flags','plat_encryption_keys'] loop
        if to_regclass(t) is not null then
            execute format('comment on table %I is %L', t,
                'Not tenant-scoped by design. Access is restricted by application-level authorization and by the database role grants.');
        end if;
    end loop;
end $$;

-- ---------------------------------------------------------------------------
-- Roles.
--
-- hatis_app: the application. Cannot bypass RLS, cannot rewrite audit history.
-- hatis_migrator: runs Flyway. Owns the schema; not used at runtime.
--
-- These are created only when they do not already exist, so a managed database
-- where the operator provisioned roles is still supported.
-- ---------------------------------------------------------------------------
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'hatis_app') then
        create role hatis_app login;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'hatis_migrator') then
        create role hatis_migrator login;
    end if;
end $$;

-- The application must not be able to bypass row level security, ever.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'hatis_app' and rolbypassrls) then
        alter role hatis_app nobypassrls;
    end if;
end $$;

grant usage on schema public to hatis_app;
grant select, insert, update, delete on all tables in schema public to hatis_app;
grant usage, select on all sequences in schema public to hatis_app;
alter default privileges in schema public grant select, insert, update, delete on tables to hatis_app;

-- System roles and workflow templates are maintained by migrations, never at
-- runtime, so the application cannot write them.
revoke insert, update, delete on auth_roles from hatis_app;
revoke insert, update, delete on wf_definitions from hatis_app;

revoke update, delete on aud_audit_logs from hatis_app;
revoke update, delete on wf_history from hatis_app;
revoke update, delete on dep_deployment_history from hatis_app;

comment on schema public is
    'Application schema. hatis_app has no BYPASSRLS and no update/delete on the append-only history tables.';
