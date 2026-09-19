-- ============================================================================
-- V1_011..V1_013 — Integrations, analytics, notifications
-- ============================================================================

create table int_integrations (
    id                uuid primary key,
    organization_id   uuid         not null,
    type              varchar(40)  not null
                      check (type in ('GITHUB','GITLAB','BITBUCKET','AWS','AZURE','GCP','SLACK','GENERIC_WEBHOOK')),
    name              varchar(120) not null,
    config            jsonb        not null default '{}'::jsonb,
    credential_ref    varchar(512),
    status            varchar(20)  not null default 'PENDING'
                      check (status in ('PENDING','CONNECTED','ERROR','DISCONNECTED')),
    last_used_at      timestamptz,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    version           bigint       not null default 0,
    constraint uq_int_integrations_org_name unique (organization_id, name)
);

create table int_webhook_endpoints (
    id                  uuid primary key,
    organization_id     uuid         not null,
    url                 varchar(512) not null,
    description         varchar(512),
    events              text[]       not null default '{}',
    -- HMAC secret used to sign deliveries, encrypted at rest.
    secret_ciphertext   text         not null,
    dek_id              varchar(64)  not null,
    active              boolean      not null default true,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now(),
    version             bigint       not null default 0
);

create table int_webhook_deliveries (
    id               uuid primary key,
    endpoint_id      uuid         not null,
    organization_id  uuid         not null,
    event_id         uuid         not null,
    event_type       varchar(120) not null,
    status           varchar(20)  not null default 'PENDING'
                     check (status in ('PENDING','DELIVERED','FAILED','SKIPPED')),
    attempts         integer      not null default 0,
    response_status  integer,
    next_attempt_at  timestamptz,
    last_error       varchar(1000),
    created_at       timestamptz  not null default now(),
    delivered_at     timestamptz
);

create index ix_int_deliveries_pending on int_webhook_deliveries (status, next_attempt_at)
    where status = 'PENDING';

-- ---------------------------------------------------------------------------
-- Analytics
-- ---------------------------------------------------------------------------

create table anl_data_sources (
    id                uuid primary key,
    organization_id   uuid         not null,
    name              varchar(120) not null,
    type              varchar(40)  not null
                      check (type in ('CMS','POSTGRES','MYSQL','SQLSERVER','CSV','EXCEL','S3','API','WAREHOUSE')),
    connection_ref    varchar(512),
    schedule          varchar(64),
    status            varchar(20)  not null default 'PENDING'
                      check (status in ('PENDING','ACTIVE','ERROR','PAUSED')),
    last_synced_at    timestamptz,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    version           bigint       not null default 0,
    constraint uq_anl_sources_org_name unique (organization_id, name)
);

create table anl_datasets (
    id                uuid primary key,
    organization_id   uuid         not null,
    data_source_id    uuid,
    name              varchar(120) not null,
    query             text,
    schema            jsonb        not null default '{}'::jsonb,
    refresh_mode      varchar(20)  not null default 'ON_DEMAND'
                      check (refresh_mode in ('ON_DEMAND','SCHEDULED','INCREMENTAL')),
    status            varchar(20)  not null default 'DRAFT'
                      check (status in ('DRAFT','READY','ERROR')),
    row_count         bigint,
    last_refreshed_at timestamptz,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    version           bigint       not null default 0
);

create table anl_metrics (
    id               uuid primary key,
    organization_id  uuid          not null,
    key              varchar(120)  not null,
    name             varchar(200)  not null,
    unit             varchar(32),
    aggregation      varchar(20)   not null default 'SUM'
                     check (aggregation in ('SUM','AVG','MIN','MAX','COUNT','LAST')),
    value            numeric(24,6) not null,
    dimension        jsonb         not null default '{}'::jsonb,
    bucket_start     timestamptz   not null,
    created_at       timestamptz   not null default now()
);

create index ix_anl_metrics_lookup on anl_metrics (organization_id, key, bucket_start desc);

create table anl_dashboards (
    id               uuid primary key,
    organization_id  uuid         not null,
    project_id       uuid,
    name             varchar(200) not null,
    layout           jsonb        not null default '{}'::jsonb,
    visibility       varchar(20)  not null default 'PRIVATE'
                     check (visibility in ('PRIVATE','PROJECT','ORGANIZATION')),
    created_by       uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0
);

create table anl_reports (
    id               uuid primary key,
    organization_id  uuid         not null,
    dashboard_id     uuid,
    name             varchar(200) not null,
    format           varchar(10)  not null default 'CSV' check (format in ('CSV','XLSX','PDF','JSON')),
    schedule         varchar(64),
    recipients       text[]       not null default '{}',
    last_run_at      timestamptz,
    next_run_at      timestamptz,
    status           varchar(20)  not null default 'ACTIVE' check (status in ('ACTIVE','PAUSED','ERROR')),
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0
);

create table anl_alerts (
    id                 uuid primary key,
    organization_id    uuid          not null,
    metric_key         varchar(120)  not null,
    condition          varchar(10)   not null check (condition in ('GT','GTE','LT','LTE','EQ')),
    threshold          numeric(24,6) not null,
    window_minutes     integer       not null default 60,
    notify_channels    text[]        not null default '{}',
    status             varchar(20)   not null default 'ACTIVE' check (status in ('ACTIVE','PAUSED','TRIGGERED')),
    last_triggered_at  timestamptz,
    created_at         timestamptz   not null default now(),
    updated_at         timestamptz   not null default now(),
    version            bigint        not null default 0
);

-- ---------------------------------------------------------------------------
-- Notifications
-- ---------------------------------------------------------------------------

create table ntf_notifications (
    id               uuid primary key,
    organization_id  uuid          not null,
    user_id          uuid          not null,
    channel          varchar(20)   not null default 'IN_APP' check (channel in ('IN_APP','EMAIL','WEBHOOK')),
    template_key     varchar(120)  not null,
    subject          varchar(512)  not null,
    body             text          not null,
    status           varchar(20)   not null default 'PENDING'
                     check (status in ('PENDING','SENT','FAILED','READ')),
    attempts         integer       not null default 0,
    read_at          timestamptz,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    version          bigint        not null default 0
);

create index ix_ntf_user_unread on ntf_notifications (user_id, status, created_at desc);

do $$
declare t text;
begin
    foreach t in array array[
        'int_integrations','int_webhook_endpoints','anl_data_sources','anl_datasets','anl_dashboards',
        'anl_reports','anl_alerts','ntf_notifications'
    ] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
