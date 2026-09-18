-- ============================================================================
-- V1_010 — Deployment
--
-- Releases are immutable. A rollback creates a new deployment pointing at an
-- earlier release, so history is never overwritten.
-- ============================================================================

create table dep_applications (
    id               uuid primary key,
    organization_id  uuid         not null,
    project_id       uuid         not null,
    name             varchar(120) not null,
    slug             varchar(63)  not null,
    description      varchar(2000),
    runtime          varchar(20)  not null default 'CONTAINER'
                     check (runtime in ('CONTAINER','STATIC','FUNCTION')),
    default_port     integer      not null default 8080 check (default_port between 1 and 65535),
    cpu_request      varchar(16),
    cpu_limit        varchar(16),
    memory_request   varchar(16),
    memory_limit     varchar(16),
    health_path      varchar(255),
    readiness_path   varchar(255),
    created_by       uuid,
    archived_at      timestamptz,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    -- An application belongs to a project and is deployed into any of its
    -- environments, so uniqueness is per project rather than per environment.
    constraint uq_dep_applications_slug unique (organization_id, project_id, slug)
);

create table dep_releases (
    id               uuid primary key,
    organization_id  uuid          not null,
    application_id   uuid          not null,
    -- Named release_version, not version: BaseEntity already maps the JPA
    -- optimistic-lock column to "version", and a table cannot declare it twice.
    release_version  varchar(120)  not null,
    image            varchar(512)  not null,
    -- Pinning by digest means "deploy the same thing again" is actually possible.
    digest           varchar(128),
    source_type      varchar(20)   not null default 'REGISTRY'
                     check (source_type in ('REGISTRY','GITHUB','GITLAB','BITBUCKET','UPLOAD','EXTERNAL_PIPELINE')),
    source_ref       varchar(512),
    git_commit       varchar(64),
    sbom_ref         varchar(512),
    scan_status      varchar(20)   not null default 'PENDING'
                     check (scan_status in ('PENDING','PASSED','FAILED','SKIPPED')),
    scan_summary     varchar(1000),
    status           varchar(20)   not null default 'CREATED'
                     check (status in ('CREATED','READY','REJECTED','SUPERSEDED')),
    created_by       uuid,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    version          bigint        not null default 0,
    constraint uq_dep_releases_release unique (application_id, release_version)
);

create table dep_deployments (
    id               uuid primary key,
    organization_id  uuid         not null,
    environment_id   uuid         not null,
    application_id   uuid         not null,
    release_id       uuid         not null,
    status           varchar(20)  not null default 'PENDING'
                     check (status in ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED','ROLLED_BACK')),
    strategy         varchar(20)  not null default 'ROLLING'
                     check (strategy in ('ROLLING','RECREATE','BLUE_GREEN','CANARY')),
    replicas         integer      not null default 1 check (replicas >= 0),
    health_status    varchar(20)  not null default 'UNKNOWN'
                     check (health_status in ('UNKNOWN','HEALTHY','DEGRADED','UNHEALTHY')),
    operation_id     uuid,
    started_at       timestamptz,
    finished_at      timestamptz,
    rolled_back_at   timestamptz,
    created_by       uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0
);

create index ix_dep_deployments_env on dep_deployments (organization_id, environment_id, created_at desc);
create index ix_dep_deployments_app on dep_deployments (application_id, status);

-- Append-only: deployment history is never overwritten, including on rollback.
create table dep_deployment_history (
    id               uuid primary key,
    deployment_id    uuid          not null,
    organization_id  uuid          not null,
    state            varchar(40)   not null,
    message          varchar(2000),
    occurred_at      timestamptz   not null default now()
);

create index ix_dep_history_deployment on dep_deployment_history (deployment_id, occurred_at);

revoke update, delete on dep_deployment_history from public;

create table dep_config_entries (
    id               uuid primary key,
    organization_id  uuid         not null,
    environment_id   uuid         not null,
    application_id   uuid         not null,
    key              varchar(255) not null,
    value            varchar(4000),
    -- When true, `value` is null and the material lives in `ciphertext`.
    secret           boolean      not null default false,
    ciphertext       text,
    dek_id           varchar(64),
    updated_by       uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_dep_config unique (organization_id, environment_id, application_id, key),
    constraint ck_dep_config_shape check ((secret and ciphertext is not null) or (not secret))
);

comment on table dep_config_entries is
    'Environment configuration. Secret values are write-only: the API reports that a key is set, never what it is.';

-- Deployment tokens let an external pipeline trigger a deployment without
-- holding a long-lived user credential.
create table dep_deployment_tokens (
    id               uuid primary key,
    organization_id  uuid         not null,
    environment_id   uuid         not null,
    name             varchar(120) not null,
    token_hash       varchar(64)  not null,
    token_prefix     varchar(16)  not null,
    last_used_at     timestamptz,
    expires_at       timestamptz,
    revoked_at       timestamptz,
    created_by       uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_dep_deployment_tokens_hash unique (token_hash)
);

create index ix_dep_deployment_tokens_env on dep_deployment_tokens (organization_id, environment_id);

do $$
declare t text;
begin
    foreach t in array array[
        'dep_applications','dep_releases','dep_deployments','dep_config_entries','dep_deployment_tokens'
    ] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
