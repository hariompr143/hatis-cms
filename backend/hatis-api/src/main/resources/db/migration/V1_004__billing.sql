-- ============================================================================
-- V1_004 — Billing: plans, subscriptions, entitlements, usage, invoices
--
-- Billing publishes entitlements; other contexts consult them. No payment logic
-- lives anywhere near deployment or infrastructure code.
--   Billing -> Entitlements -> Resource limits
-- ============================================================================

create table bill_plans (
    id            uuid primary key,
    code          varchar(64)   not null unique,
    name          varchar(120)  not null,
    description   varchar(2000),
    status        varchar(20)   not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED','HIDDEN')),
    currency      char(3)       not null default 'USD',
    billing_interval varchar(10) not null default 'month' check (billing_interval in ('month','year')),
    amount        numeric(19,4) not null default 0,
    trial_days    integer       not null default 0,
    sort_order    integer       not null default 0,
    created_at    timestamptz   not null default now(),
    updated_at    timestamptz   not null default now(),
    version       bigint        not null default 0
);

-- Limits are rows, not columns, so a new limit is data rather than a migration
-- that every plan must be backfilled for.
create table bill_plan_limits (
    id            uuid primary key,
    plan_id       uuid         not null,
    limit_key     varchar(64)  not null,
    limit_value   bigint,
    unlimited     boolean      not null default false,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    version       bigint       not null default 0,
    constraint uq_bill_plan_limits unique (plan_id, limit_key),
    constraint ck_bill_plan_limit_shape check (unlimited or limit_value is not null)
);

create table bill_entitlement_codes (
    id            uuid primary key,
    plan_id       uuid         not null,
    code          varchar(120) not null,
    enabled       boolean      not null default true,
    value         varchar(512),
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    version       bigint       not null default 0,
    constraint uq_bill_entitlement unique (plan_id, code)
);

create table bill_subscriptions (
    id                        uuid primary key,
    organization_id           uuid         not null,
    plan_id                   uuid         not null,
    status                    varchar(20)  not null default 'ACTIVE'
                              check (status in ('TRIALING','ACTIVE','PAST_DUE','CANCELED','EXPIRED')),
    provider                  varchar(32)  not null default 'none',
    provider_subscription_id  varchar(128),
    current_period_start      timestamptz,
    current_period_end        timestamptz,
    cancel_at_period_end      boolean      not null default false,
    trial_ends_at             timestamptz,
    created_at                timestamptz  not null default now(),
    updated_at                timestamptz  not null default now(),
    version                   bigint       not null default 0
);

create index ix_bill_subscriptions_org on bill_subscriptions (organization_id, status);

create table bill_usage_records (
    id               uuid primary key,
    organization_id  uuid          not null,
    metric_key       varchar(64)   not null,
    quantity         numeric(19,4) not null,
    unit             varchar(32)   not null,
    occurred_at      timestamptz   not null,
    aggregated       boolean       not null default false,
    source           varchar(64),
    created_at       timestamptz   not null default now()
);

create index ix_bill_usage_org_time on bill_usage_records (organization_id, metric_key, occurred_at);

create table bill_invoices (
    id                  uuid primary key,
    organization_id     uuid          not null,
    provider_invoice_id varchar(128),
    number              varchar(64),
    status              varchar(20)   not null default 'DRAFT'
                        check (status in ('DRAFT','OPEN','PAID','VOID','UNCOLLECTIBLE')),
    currency            char(3)       not null default 'USD',
    subtotal            numeric(19,4) not null default 0,
    tax                 numeric(19,4) not null default 0,
    total               numeric(19,4) not null default 0,
    period_start        timestamptz,
    period_end          timestamptz,
    issued_at           timestamptz,
    due_at              timestamptz,
    paid_at             timestamptz,
    created_at          timestamptz   not null default now(),
    updated_at          timestamptz   not null default now(),
    version             bigint        not null default 0
);

create index ix_bill_invoices_org on bill_invoices (organization_id, status);

create table bill_payment_transactions (
    id                   uuid primary key,
    organization_id      uuid          not null,
    invoice_id           uuid,
    provider             varchar(32)   not null,
    provider_payment_id  varchar(128),
    amount               numeric(19,4) not null,
    currency             char(3)       not null default 'USD',
    status               varchar(20)   not null
                         check (status in ('PENDING','SUCCEEDED','FAILED','REFUNDED')),
    failure_code         varchar(64),
    idempotency_key      varchar(128)  not null,
    created_at           timestamptz   not null default now(),
    updated_at           timestamptz   not null default now(),
    version              bigint        not null default 0,
    -- A payment is never attempted twice for the same intent, even if the
    -- customer's pipeline retries.
    constraint uq_bill_payment_idempotency unique (organization_id, idempotency_key)
);

comment on column bill_payment_transactions.idempotency_key is
    'Financial mutations are never blind-retried. The same key always resolves to the same transaction.';

-- ---------------------------------------------------------------------------
-- Starter / Professional / Enterprise catalogue.
-- Plans are data: the application never contains a hard-coded plan check.
-- ---------------------------------------------------------------------------
insert into bill_plans (id, code, name, description, amount, billing_interval, trial_days, sort_order) values
    (gen_random_uuid(), 'starter',      'Starter',      'CMS and hosting for a single site',      29,  'month', 14, 1),
    (gen_random_uuid(), 'professional', 'Professional', 'CMS, analytics and integrations',        149, 'month', 14, 2),
    (gen_random_uuid(), 'enterprise',   'Enterprise',   'Business applications, SSO and advanced security', 799, 'month', 0, 3),
    (gen_random_uuid(), 'private',      'Private Enterprise', 'Full platform deployed into customer infrastructure', 0, 'month', 0, 4);

insert into bill_plan_limits (id, plan_id, limit_key, limit_value, unlimited)
select gen_random_uuid(), p.id, l.limit_key, l.limit_value, l.unlimited
from bill_plans p
cross join (values
    ('projects',        3::bigint,  false),
    ('environments',    9::bigint,  false),
    ('applications',    5::bigint,  false),
    ('deployments',     20::bigint, false),
    ('users',           10::bigint, false),
    ('api_keys',        5::bigint,  false),
    ('storage_gib',     50::bigint, false),
    ('asset_count',     5000::bigint, false),
    ('content_items',   2000::bigint, false),
    ('cpu_milli',       2000::bigint, false),
    ('memory_mib',      4096::bigint, false),
    ('bandwidth_gib',   500::bigint, false),
    ('databases',       1::bigint,  false),
    ('domains',         5::bigint,  false),
    ('webhooks',        5::bigint,  false),
    ('dashboards',      5::bigint,  false),
    ('api_requests_month', 1000000::bigint, false),
    ('builds_month',    100::bigint, false)
) as l(limit_key, limit_value, unlimited)
where p.code = 'starter';

insert into bill_plan_limits (id, plan_id, limit_key, limit_value, unlimited)
select gen_random_uuid(), p.id, l.limit_key, l.limit_value, l.unlimited
from bill_plans p
cross join (values
    ('projects',        25::bigint,  false),
    ('environments',    100::bigint, false),
    ('applications',    50::bigint,  false),
    ('deployments',     200::bigint, false),
    ('users',           100::bigint, false),
    ('api_keys',        50::bigint,  false),
    ('storage_gib',     500::bigint, false),
    ('asset_count',     100000::bigint, false),
    ('content_items',   50000::bigint, false),
    ('cpu_milli',       16000::bigint, false),
    ('memory_mib',      32768::bigint, false),
    ('bandwidth_gib',   5000::bigint, false),
    ('databases',       5::bigint,   false),
    ('domains',         50::bigint,  false),
    ('webhooks',        50::bigint,  false),
    ('dashboards',      50::bigint,  false),
    ('api_requests_month', null::bigint, true),
    ('builds_month',    1000::bigint, false)
) as l(limit_key, limit_value, unlimited)
where p.code = 'professional';

insert into bill_plan_limits (id, plan_id, limit_key, limit_value, unlimited)
select gen_random_uuid(), p.id, l.limit_key, null, true
from bill_plans p
cross join (values
    ('projects'),('environments'),('applications'),('deployments'),('users'),('api_keys'),
    ('storage_gib'),('asset_count'),('content_items'),('cpu_milli'),('memory_mib'),
    ('bandwidth_gib'),('databases'),('domains'),('webhooks'),('dashboards'),
    ('api_requests_month'),('builds_month')
) as l(limit_key)
where p.code in ('enterprise','private');

-- Byte-accurate storage enforcement and the per-object ceiling. storage_gib above
-- is the customer-facing unit; these two are what the platform actually enforces.
insert into bill_plan_limits (id, plan_id, limit_key, limit_value, unlimited)
select gen_random_uuid(), p.id, l.limit_key, l.limit_value, l.unlimited
from bill_plans p
join (values
    ('starter',      'storage_bytes',   53687091200::bigint,        false),
    ('starter',      'asset_max_bytes', 1073741824::bigint,         false),
    ('professional', 'storage_bytes',   536870912000::bigint,       false),
    ('professional', 'asset_max_bytes', 5368709120::bigint,         false),
    ('enterprise',   'storage_bytes',   5368709120000::bigint,      false),
    ('enterprise',   'asset_max_bytes', 21474836480::bigint,        false),
    ('private',      'storage_bytes',   null::bigint,               true),
    ('private',      'asset_max_bytes', null::bigint,               true)
) as l(plan_code, limit_key, limit_value, unlimited) on l.plan_code = p.code;

insert into bill_entitlement_codes (id, plan_id, code, enabled, value)
select gen_random_uuid(), p.id, e.code, e.enabled, e.value
from bill_plans p
cross join (values
    ('cms',                     true,  null),
    ('digital_assets',          true,  null),
    ('hosting',                 true,  null),
    ('custom_domains',          true,  null),
    ('analytics',               true,  null),
    ('integrations',            true,  null),
    ('workflow',                true,  null),
    ('business_applications',   true,  null),
    ('sso',                     true,  null),
    ('saml',                    true,  null),
    ('scim',                    true,  null),
    ('audit_export',            true,  null),
    ('private_deployment',      true,  null),
    ('advanced_security',       true,  null),
    ('support_sla_hours',       true,  '4')
) as e(code, enabled, value)
where p.code = 'enterprise';

insert into bill_entitlement_codes (id, plan_id, code, enabled, value)
select gen_random_uuid(), p.id, e.code, e.enabled, e.value
from bill_plans p
cross join (values
    ('cms',                   true,  null),
    ('digital_assets',        true,  null),
    ('hosting',               true,  null),
    ('custom_domains',        true,  null),
    ('analytics',             true,  null),
    ('integrations',          true,  null),
    ('workflow',              true,  null),
    ('business_applications', false, null),
    ('sso',                   false, null),
    ('saml',                  false, null),
    ('scim',                  false, null),
    ('audit_export',          false, null),
    ('private_deployment',    false, null),
    ('advanced_security',     false, null),
    ('support_sla_hours',     true,  '48')
) as e(code, enabled, value)
where p.code = 'professional';

insert into bill_entitlement_codes (id, plan_id, code, enabled, value)
select gen_random_uuid(), p.id, e.code, e.enabled, e.value
from bill_plans p
cross join (values
    ('cms',                   true,  null),
    ('digital_assets',        true,  null),
    ('hosting',               true,  null),
    ('custom_domains',        true,  null),
    ('analytics',             false, null),
    ('integrations',          false, null),
    ('workflow',              false, null),
    ('business_applications', false, null),
    ('sso',                   false, null),
    ('saml',                  false, null),
    ('scim',                  false, null),
    ('audit_export',          false, null),
    ('private_deployment',    false, null),
    ('advanced_security',     false, null),
    ('support_sla_hours',     true,  '72')
) as e(code, enabled, value)
where p.code = 'starter';

insert into bill_entitlement_codes (id, plan_id, code, enabled, value)
select gen_random_uuid(), p.id, e.code, true, e.value
from bill_plans p
cross join (values
    ('cms', null), ('digital_assets', null), ('hosting', null), ('custom_domains', null),
    ('analytics', null), ('integrations', null), ('workflow', null),
    ('business_applications', null), ('sso', null), ('saml', null), ('scim', null),
    ('audit_export', null), ('private_deployment', null), ('advanced_security', null),
    ('support_sla_hours', '1')
) as e(code, value)
where p.code = 'private';

do $$
declare t text;
begin
    foreach t in array array[
        'bill_plans','bill_plan_limits','bill_entitlement_codes','bill_subscriptions','bill_invoices','bill_payment_transactions'
    ] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
