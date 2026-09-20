-- ============================================================================
-- V1_007 — Digital assets (DAM)
--
-- Assets are immutable versions plus mutable metadata. Bytes live in object
-- storage; this schema holds references, checksums and scan state.
-- ============================================================================

create table asset_folders (
    id               uuid primary key,
    organization_id  uuid         not null,
    project_id       uuid         not null,
    parent_id        uuid,
    name             varchar(200) not null,
    path             varchar(1000) not null,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_asset_folders_path unique (organization_id, project_id, path)
);

create table assets (
    id                  uuid primary key,
    organization_id     uuid          not null,
    project_id          uuid          not null,
    folder_id           uuid,
    filename            varchar(255)  not null,
    content_type        varchar(160)  not null,
    byte_size           bigint        not null check (byte_size >= 0),
    checksum_sha256     varchar(64)   not null,
    storage_binding_id  uuid,
    storage_key         varchar(1024) not null,
    -- Nothing is delivered until the malware scan passes.
    scan_status         varchar(20)   not null default 'PENDING'
                        check (scan_status in ('PENDING','CLEAN','INFECTED','ERROR','SKIPPED')),
    scan_detail         varchar(512),
    classification      varchar(20)   not null default 'CONFIDENTIAL'
                        check (classification in ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
    status              varchar(20)   not null default 'UPLOADED'
                        check (status in ('UPLOADED','QUARANTINED','READY','ARCHIVED','DELETED')),
    current_version_id  uuid,
    width               integer,
    height              integer,
    created_by          uuid,
    created_at          timestamptz   not null default now(),
    updated_at          timestamptz   not null default now(),
    version             bigint        not null default 0,
    deleted_at          timestamptz
);

create index ix_assets_org_project on assets (organization_id, project_id, status);
create index ix_assets_checksum    on assets (organization_id, checksum_sha256);

create table asset_versions (
    id               uuid primary key,
    asset_id         uuid          not null,
    organization_id  uuid          not null,
    version_number   integer       not null,
    byte_size        bigint        not null,
    checksum_sha256  varchar(64)   not null,
    storage_key      varchar(1024) not null,
    created_by       uuid,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    version          bigint        not null default 0,
    constraint uq_asset_versions unique (asset_id, version_number)
);

create table asset_metadata (
    id               uuid primary key,
    asset_id         uuid         not null,
    organization_id  uuid         not null,
    key              varchar(120) not null,
    value            varchar(2000),
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_asset_metadata unique (asset_id, key)
);

create table asset_renditions (
    id               uuid primary key,
    asset_id         uuid          not null,
    organization_id  uuid          not null,
    profile          varchar(64)   not null,
    width            integer,
    height           integer,
    format           varchar(16),
    storage_key      varchar(1024) not null,
    byte_size        bigint,
    checksum_sha256  varchar(64),
    status           varchar(20)   not null default 'PENDING'
                     check (status in ('PENDING','READY','ERROR')),
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    version          bigint        not null default 0,
    constraint uq_asset_renditions unique (asset_id, profile)
);

comment on table assets is
    'Asset records. Delivery is always through short-lived presigned URLs; the bucket is never public.';

do $$
declare t text;
begin
    foreach t in array array['assets','asset_versions','asset_metadata','asset_renditions','asset_folders'] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
