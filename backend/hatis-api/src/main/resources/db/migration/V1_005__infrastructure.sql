-- ============================================================================
-- V1_005 — Infrastructure bindings, secrets and tenant configuration
--
-- Everything here is a *reference* to something that lives elsewhere: a bucket,
-- a database, a cluster, a secret manager path. Connection credentials are
-- stored encrypted and are never returned by a list endpoint.
-- ============================================================================

create table infra_storage_bindings (
    id                uuid primary key,
    organization_id   uuid          not null,
    name              varchar(120)  not null,
    provider          varchar(20)   not null check (provider in ('S3','AZURE_BLOB','GCS','PRIVATE_S3')),
    region            varchar(64),
    bucket            varchar(255)  not null,
    endpoint          varchar(512),
    path_style        boolean       not null default false,
    -- Reference into the secret store, never the credential itself.
    credential_ref    varchar(512),
    key_prefix        varchar(255),
    status            varchar(20)   not null default 'PENDING'
                      check (status in ('PENDING','ACTIVE','DEGRADED','ERROR')),
    last_checked_at   timestamptz,
    created_at        timestamptz   not null default now(),
    updated_at        timestamptz   not null default now(),
    version           bigint        not null default 0,
    constraint uq_infra_storage_org_name unique (organization_id, name)
);

create table infra_database_instances (
    id                     uuid primary key,
    organization_id        uuid         not null,
    name                   varchar(120) not null,
    engine                 varchar(20)  not null check (engine in ('POSTGRES','MYSQL','SQLSERVER','ORACLE')),
    engine_version         varchar(32),
    -- MANAGED: the platform provisions and operates it.
    -- BYO:     the customer connects an existing database.
    mode                   varchar(20)  not null check (mode in ('MANAGED','BYO')),
    cpu_milli              integer,
    memory_mib             integer,
    storage_gib            integer,
    backup_enabled         boolean      not null default true,
    backup_retention_days  integer      not null default 7,
    -- Recovery objectives the customer can read and plan against.
    rpo_minutes            integer,
    rto_minutes            integer,
    status                 varchar(20)  not null default 'PENDING'
                           check (status in ('PENDING','PROVISIONING','ACTIVE','DEGRADED','ERROR','DELETED')),
    created_at             timestamptz  not null default now(),
    updated_at             timestamptz  not null default now(),
    version                bigint       not null default 0,
    constraint uq_infra_db_org_name unique (organization_id, name)
);

create table infra_database_bindings (
    id                     uuid primary key,
    organization_id        uuid          not null,
    instance_id            uuid          not null,
    name                   varchar(120)  not null,
    host                   varchar(255),
    port                   integer,
    database_name          varchar(120),
    -- Encrypted connection string. Never returned by an API after creation.
    connection_ciphertext  text,
    dek_id                 varchar(64),
    private_link           boolean       not null default false,
    status                 varchar(20)   not null default 'PENDING'
                           check (status in ('PENDING','ACTIVE','ERROR')),
    last_checked_at        timestamptz,
    created_at             timestamptz   not null default now(),
    updated_at             timestamptz   not null default now(),
    version                bigint        not null default 0,
    constraint uq_infra_db_binding_org_name unique (organization_id, name)
);

comment on column infra_database_bindings.connection_ciphertext is
    'AES-256-GCM ciphertext under the tenant DEK. There is no endpoint that returns this value.';

create table infra_kubernetes_clusters (
    id                    uuid primary key,
    organization_id       uuid         not null,
    name                  varchar(120) not null,
    api_endpoint          varchar(512) not null,
    -- Pinned cluster CA so a hostile network cannot impersonate the API server.
    ca_bundle_ciphertext  text,
    credential_ref        varchar(512),
    -- PLATFORM: our cluster. CUSTOMER: theirs, reached through the connector.
    mode                  varchar(20)  not null default 'PLATFORM'
                          check (mode in ('PLATFORM','CUSTOMER')),
    status                varchar(20)  not null default 'PENDING'
                          check (status in ('PENDING','ACTIVE','DEGRADED','ERROR')),
    last_checked_at       timestamptz,
    created_at            timestamptz  not null default now(),
    updated_at            timestamptz  not null default now(),
    version               bigint       not null default 0
);

create table infra_secrets (
    id               uuid primary key,
    organization_id  uuid          not null,
    scope_type       varchar(20)   not null
                     check (scope_type in ('ORGANIZATION','PROJECT','ENVIRONMENT','APPLICATION')),
    scope_id         uuid          not null,
    key              varchar(255)  not null,
    ciphertext       text          not null,
    dek_id           varchar(64)   not null,
    version          integer       not null default 1,
    classification   varchar(20)   not null default 'RESTRICTED',
    rotated_at       timestamptz,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    constraint uq_infra_secrets_scope_key unique (organization_id, scope_type, scope_id, key)
);

comment on table infra_secrets is
    'Envelope-encrypted tenant secrets. Values are write-only: the API confirms a secret is set but never returns it.';

do $$
declare t text;
begin
    foreach t in array array[
        'infra_storage_bindings','infra_database_instances','infra_database_bindings','infra_kubernetes_clusters'
    ] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
