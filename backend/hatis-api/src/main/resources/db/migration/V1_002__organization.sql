-- ============================================================================
-- V1_002 — Organization: the tenant resource hierarchy
--
--   Organization -> Workspace -> Project -> Environment
--
-- org_organizations.organization_id is the row's own id, so a single row level
-- security policy shape covers every tenant table without a special case here.
-- ============================================================================

create table org_organizations (
    id                       uuid primary key,
    organization_id          uuid         not null,
    name                     varchar(200) not null,
    slug                     citext       not null,
    status                   varchar(20)  not null default 'ACTIVE'
                             check (status in ('PENDING','ACTIVE','SUSPENDED','CLOSED')),
    plan_code                varchar(64)  not null default 'starter',
    region                   varchar(32),
    isolation_mode           varchar(32)  not null default 'SHARED_SCHEMA'
                             check (isolation_mode in
                                 ('SHARED_SCHEMA','DEDICATED_SCHEMA','DEDICATED_DATABASE','CUSTOMER_MANAGED')),
    -- Envelope encryption: the tenant DEK, wrapped by a KEK in the secret store.
    encryption_key_id        varchar(64),
    encryption_key_wrapped   text,
    deleted_at               timestamptz,
    created_at               timestamptz  not null default now(),
    updated_at               timestamptz  not null default now(),
    version                  bigint       not null default 0,
    constraint uq_org_organizations_slug unique (slug),
    constraint uq_org_organizations_self check (id = organization_id)
);

comment on column org_organizations.encryption_key_wrapped is
    'Tenant data encryption key wrapped by a KEK from the secret store. Revoking it cryptographically erases the tenant''s protected values.';

create table org_workspaces (
    id               uuid primary key,
    organization_id  uuid         not null,
    name             varchar(200) not null,
    slug             varchar(63)  not null,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_org_workspaces_org_slug unique (organization_id, slug)
);

create table org_projects (
    id               uuid primary key,
    organization_id  uuid          not null,
    workspace_id     uuid,
    name             varchar(200)  not null,
    slug             varchar(63)   not null,
    description      varchar(2000),
    status           varchar(20)   not null default 'ACTIVE'
                     check (status in ('ACTIVE','ARCHIVED','DELETED')),
    deleted_at       timestamptz,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    version          bigint        not null default 0,
    constraint uq_org_projects_org_slug unique (organization_id, slug)
);

create index ix_org_projects_org on org_projects (organization_id);

create table org_environments (
    id               uuid primary key,
    organization_id  uuid         not null,
    project_id       uuid         not null,
    name             varchar(100) not null,
    slug             varchar(63)  not null,
    kind             varchar(20)  not null
                     check (kind in ('DEVELOPMENT','PREVIEW','QA','UAT','STAGING','PRODUCTION','CUSTOM')),
    -- Production is a property, not a name: it carries a stronger authorization
    -- requirement and cannot be deleted while it holds an active deployment.
    production       boolean      not null default false,
    order_index      integer      not null default 0,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_org_environments_project_slug unique (organization_id, project_id, slug)
);

create index ix_org_environments_project on org_environments (organization_id, project_id);

create table org_memberships (
    id               uuid primary key,
    organization_id  uuid         not null,
    user_id          uuid         not null,
    role             varchar(64)  not null,
    status           varchar(20)  not null default 'INVITED'
                     check (status in ('INVITED','ACTIVE','SUSPENDED','REMOVED')),
    invited_by       uuid,
    invited_at       timestamptz,
    joined_at        timestamptz,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_org_memberships_org_user unique (organization_id, user_id)
);

create index ix_org_memberships_user on org_memberships (user_id, status);

-- Support grants: time-boxed, explicitly authorized, audited operator access.
create table org_support_grants (
    id               uuid primary key,
    organization_id  uuid          not null,
    operator_id      uuid          not null,
    reason           varchar(2000) not null,
    approved_by      uuid          not null,
    granted_at       timestamptz   not null default now(),
    expires_at       timestamptz   not null,
    revoked_at       timestamptz,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    version          bigint        not null default 0,
    -- A support session must never outlive a working day.
    constraint ck_org_support_grants_duration check (expires_at - granted_at <= interval '8 hours')
);

create index ix_org_support_grants_active on org_support_grants (organization_id, expires_at)
    where revoked_at is null;

do $$
declare t text;
begin
    foreach t in array array[
        'org_organizations','org_workspaces','org_projects','org_environments',
        'org_memberships','org_support_grants'
    ] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
