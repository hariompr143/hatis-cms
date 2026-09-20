-- ============================================================================
-- V1_009 — CMS
--
-- Content items are mutable pointers; content versions are immutable snapshots.
-- Publishing never mutates a published version, which is what makes rollback
-- honest and audit meaningful.
-- ============================================================================

create table cms_content_types (
    id               uuid primary key,
    organization_id  uuid         not null,
    project_id       uuid         not null,
    name             varchar(120) not null,
    slug             varchar(63)  not null,
    description      varchar(2000),
    -- JSON Schema describing the body. Validated server side on every write;
    -- the client is never trusted to have done it.
    schema           jsonb        not null,
    title_field      varchar(120) not null default 'title',
    status           varchar(20)  not null default 'ACTIVE'
                     check (status in ('DRAFT','ACTIVE','RETIRED')),
    schema_version   integer      not null default 1,
    created_by       uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_cms_content_types_slug unique (organization_id, project_id, slug)
);

create table cms_content_items (
    id                    uuid primary key,
    organization_id       uuid         not null,
    project_id            uuid         not null,
    content_type_id       uuid         not null,
    slug                  varchar(255) not null,
    locale                varchar(16)  not null default 'en',
    status                varchar(20)  not null default 'DRAFT'
                          check (status in ('DRAFT','IN_REVIEW','APPROVED','PUBLISHED','ARCHIVED','DELETED')),
    current_version_id    uuid,
    published_version_id  uuid,
    workflow_instance_id  uuid,
    -- Canonical source item for translations; null on the source itself.
    source_item_id        uuid,
    created_by            uuid,
    updated_by            uuid,
    created_at            timestamptz  not null default now(),
    updated_at            timestamptz  not null default now(),
    published_at          timestamptz,
    version               bigint       not null default 0,
    deleted_at            timestamptz,
    constraint uq_cms_content_items_slug unique (organization_id, project_id, locale, slug)
);

create index ix_cms_items_project_status on cms_content_items (organization_id, project_id, status);
create index ix_cms_items_type           on cms_content_items (organization_id, content_type_id);

create table cms_content_versions (
    id               uuid primary key,
    content_item_id  uuid         not null,
    organization_id  uuid         not null,
    version_number   integer      not null,
    body             jsonb        not null,
    -- Generated, indexed text. Search is derived data, never the source of truth.
    search_text      text,
    change_note      varchar(512),
    created_by       uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_cms_content_versions unique (content_item_id, version_number)
);

create index ix_cms_versions_search on cms_content_versions
    using gin (to_tsvector('simple', coalesce(search_text, '')));

comment on table cms_content_versions is
    'Immutable snapshots. A new edit creates a new version; published versions are never mutated.';

create table cms_releases (
    id               uuid primary key,
    organization_id  uuid         not null,
    project_id       uuid         not null,
    name             varchar(200) not null,
    status           varchar(20)  not null default 'SCHEDULED'
                     check (status in ('DRAFT','SCHEDULED','PUBLISHING','PUBLISHED','FAILED','ROLLED_BACK')),
    scheduled_at     timestamptz,
    published_at     timestamptz,
    created_by       uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0
);

create table cms_release_items (
    release_id           uuid not null,
    content_item_id      uuid not null,
    content_version_id   uuid not null,
    primary key (release_id, content_item_id)
);

create table cms_taxonomy_terms (
    id               uuid primary key,
    organization_id  uuid         not null,
    project_id       uuid         not null,
    vocabulary       varchar(64)  not null,
    slug             varchar(120) not null,
    name             varchar(200) not null,
    parent_id        uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_cms_taxonomy unique (organization_id, project_id, vocabulary, slug)
);

create table cms_content_terms (
    content_item_id  uuid not null,
    term_id          uuid not null,
    primary key (content_item_id, term_id)
);

do $$
declare t text;
begin
    foreach t in array array[
        'cms_content_types','cms_content_items','cms_content_versions','cms_releases','cms_taxonomy_terms'
    ] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
