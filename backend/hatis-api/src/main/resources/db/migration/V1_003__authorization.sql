-- ============================================================================
-- V1_003 — Authorization
--
-- RBAC with hierarchical scopes and deny overrides:
--   permission = <resource-type>:<action>
--   role       = named set of permissions (system or tenant-defined)
--   binding    = (principal, role, scope)
--
-- A binding at a higher scope grants the permission at every lower scope; an
-- explicit deny at a narrower scope wins.
-- ============================================================================

create table auth_permissions (
    id                uuid primary key,
    code              varchar(120) not null unique,
    description       varchar(512),
    resource_type     varchar(64)  not null,
    action            varchar(64)  not null,
    -- Data classification the permission governs; drives UI grouping and audit.
    classification    varchar(20)  not null default 'INTERNAL'
                      check (classification in ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    version           bigint       not null default 0
);

create table auth_roles (
    id               uuid primary key,
    organization_id  uuid,             -- null marks a system role shipped with the platform
    code             varchar(64)  not null,
    name             varchar(120) not null,
    description      varchar(512),
    system           boolean      not null default false,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_auth_roles_code unique (organization_id, code)
);

create table auth_role_permissions (
    role_id        uuid not null,
    permission_id  uuid not null,
    primary key (role_id, permission_id)
);

create table auth_role_bindings (
    id               uuid primary key,
    organization_id  uuid         not null,
    role_id          uuid         not null,
    principal_type   varchar(20)  not null check (principal_type in ('USER','SERVICE_ACCOUNT','API_KEY')),
    principal_id     uuid         not null,
    scope_type       varchar(20)  not null
                     check (scope_type in ('ORGANIZATION','WORKSPACE','PROJECT','ENVIRONMENT','RESOURCE')),
    scope_id         uuid,
    -- An explicit deny at a narrow scope overrides an allow inherited from above.
    effect           varchar(10)  not null default 'ALLOW' check (effect in ('ALLOW','DENY')),
    expires_at       timestamptz,
    created_by       uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0
);

create index ix_auth_bindings_lookup on auth_role_bindings (organization_id, principal_type, principal_id, scope_type);
create index ix_auth_bindings_scope  on auth_role_bindings (organization_id, scope_type, scope_id);

-- ---------------------------------------------------------------------------
-- System roles and the permission catalogue.
--
-- Permissions are data, not code, so a new capability does not require a schema
-- change; but adding one is still a reviewed migration, not a runtime act.
-- ---------------------------------------------------------------------------

insert into auth_permissions (id, code, description, resource_type, action, classification) values
    (gen_random_uuid(), 'organization:read',      'Read organization details',        'organization', 'read',   'INTERNAL'),
    (gen_random_uuid(), 'organization:write',     'Update organization details',      'organization', 'write',  'INTERNAL'),
    (gen_random_uuid(), 'organization:delete',    'Close the organization',           'organization', 'delete', 'CONFIDENTIAL'),
    (gen_random_uuid(), 'project:read',           'Read projects',                    'project',      'read',   'INTERNAL'),
    (gen_random_uuid(), 'project:write',          'Create and update projects',       'project',      'write',  'INTERNAL'),
    (gen_random_uuid(), 'environment:read',       'Read environments',                'environment',  'read',   'INTERNAL'),
    (gen_random_uuid(), 'environment:write',      'Create and update environments',   'environment',  'write',  'INTERNAL'),
    (gen_random_uuid(), 'member:read',            'List members',                     'membership',   'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'member:write',           'Invite, change and remove members','membership',   'write',  'CONFIDENTIAL'),
    (gen_random_uuid(), 'role:read',              'Read roles and bindings',          'role',         'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'role:write',             'Assign roles and bindings',        'role',         'write',  'CONFIDENTIAL'),
    (gen_random_uuid(), 'api_key:read',           'List API keys',                    'api_key',      'read',   'RESTRICTED'),
    (gen_random_uuid(), 'api_key:write',          'Create and revoke API keys',       'api_key',      'write',  'RESTRICTED'),
    (gen_random_uuid(), 'audit:read',             'Read the audit trail',             'audit',        'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'cms:content:read',       'Read content including drafts',    'content_item', 'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'cms:content:write',      'Create and edit content',          'content_item', 'write',  'CONFIDENTIAL'),
    (gen_random_uuid(), 'cms:content:submit',     'Submit content for approval',      'content_item', 'submit', 'CONFIDENTIAL'),
    (gen_random_uuid(), 'cms:content:approve',    'Approve content',                  'content_item', 'approve','CONFIDENTIAL'),
    (gen_random_uuid(), 'cms:content:publish',    'Publish and unpublish content',    'content_item', 'publish','CONFIDENTIAL'),
    (gen_random_uuid(), 'cms:type:write',         'Define content types',             'content_type', 'write',  'INTERNAL'),
    (gen_random_uuid(), 'asset:read',             'Read assets',                      'asset',        'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'asset:write',            'Upload and update assets',         'asset',        'write',  'CONFIDENTIAL'),
    (gen_random_uuid(), 'asset:delete',           'Delete assets',                    'asset',        'delete', 'CONFIDENTIAL'),
    (gen_random_uuid(), 'deployment:read',        'Read deployments',                 'deployment',   'read',   'INTERNAL'),
    (gen_random_uuid(), 'deployment:write',       'Create deployments',               'deployment',   'write',  'INTERNAL'),
    (gen_random_uuid(), 'deployment:rollback',    'Roll back a deployment',           'deployment',   'rollback','INTERNAL'),
    (gen_random_uuid(), 'config:read',            'Read configuration keys',          'config',       'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'config:write',           'Set configuration and secrets',    'config',       'write',  'RESTRICTED'),
    (gen_random_uuid(), 'environment:production:write', 'Change production workloads','environment',  'write',  'RESTRICTED'),
    (gen_random_uuid(), 'domain:read',            'Read domains and certificates',    'domain',       'read',   'INTERNAL'),
    (gen_random_uuid(), 'domain:write',           'Add and verify domains',           'domain',       'write',  'INTERNAL'),
    (gen_random_uuid(), 'database:read',          'Read database instances',          'database',     'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'database:write',         'Provision and bind databases',     'database',     'write',  'RESTRICTED'),
    (gen_random_uuid(), 'storage:read',           'Read storage bindings',            'storage',      'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'storage:write',          'Configure storage bindings',       'storage',      'write',  'RESTRICTED'),
    (gen_random_uuid(), 'analytics:read',         'Read dashboards and reports',      'dashboard',    'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'analytics:write',        'Create dashboards and datasets',   'dashboard',    'write',  'CONFIDENTIAL'),
    (gen_random_uuid(), 'billing:read',           'Read plan, usage and invoices',    'subscription', 'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'billing:write',          'Change subscription and payment',  'subscription', 'write',  'RESTRICTED'),
    (gen_random_uuid(), 'integration:read',       'Read integrations and webhooks',   'integration',  'read',   'CONFIDENTIAL'),
    (gen_random_uuid(), 'integration:write',      'Configure integrations',           'integration',  'write',  'RESTRICTED');

-- A temp table rather than a CTE: the role/permission mapping below is a
-- second statement, and a CTE is scoped to the single statement it opens.
-- Referencing it from the next statement fails with "relation does not exist".
create temp table system_roles (
    code        text not null,
    name        text not null,
    description text not null,
    perms       text not null
) on commit drop;

insert into system_roles (code, name, description, perms)
values
        ('OWNER',              'Owner',              'Full control of the organization, including transfer and closure', '*'),
        ('ORG_ADMIN',          'Organization Admin', 'Manage members, projects, environments and integrations',
            'organization:read,organization:write,project:read,project:write,environment:read,environment:write,member:read,member:write,role:read,role:write,api_key:read,api_key:write,audit:read,cms:content:read,cms:content:write,cms:content:submit,cms:content:approve,cms:content:publish,cms:type:write,asset:read,asset:write,asset:delete,deployment:read,deployment:write,deployment:rollback,config:read,config:write,domain:read,domain:write,database:read,storage:read,analytics:read,analytics:write,billing:read,integration:read,integration:write'),
        ('SECURITY_ADMIN',     'Security Admin',     'Policies, audit, secrets and support grants',
            'organization:read,audit:read,role:read,role:write,api_key:read,api_key:write,member:read,config:read,config:write'),
        ('BILLING_ADMIN',      'Billing Admin',      'Plans, subscriptions, invoices and payment methods',
            'organization:read,billing:read,billing:write'),
        ('DEVELOPER',          'Developer',          'Deployments, environments, configuration and API keys',
            'project:read,environment:read,environment:write,deployment:read,deployment:write,deployment:rollback,config:read,config:write,api_key:read,api_key:write,domain:read,domain:write,database:read,storage:read,analytics:read,cms:content:read,asset:read'),
        ('EDITOR',             'Editor',             'Content and assets, with publishing where allowed',
            'project:read,cms:content:read,cms:content:write,cms:content:submit,cms:content:publish,asset:read,asset:write,analytics:read'),
        ('ANALYST',            'Analyst',            'Dashboards, reports and read-only content',
            'project:read,analytics:read,analytics:write,cms:content:read,asset:read'),
        ('VIEWER',             'Viewer',             'Read-only access within the bound scope',
            'organization:read,project:read,environment:read,cms:content:read,asset:read,deployment:read,analytics:read,domain:read,database:read,storage:read,billing:read')
;

insert into auth_roles (id, organization_id, code, name, description, system)
select gen_random_uuid(), null, code, name, description, true from system_roles;

insert into auth_role_permissions (role_id, permission_id)
select r.id, p.id
from auth_roles r
join system_roles sr on sr.code = r.code and r.system
join auth_permissions p
  on sr.perms = '*' or p.code = any (string_to_array(sr.perms, ','));

-- Every member gets an organization-scope binding matching their membership role.
-- Narrower bindings are added explicitly by an administrator.
insert into auth_role_bindings (id, organization_id, role_id, principal_type, principal_id, scope_type, scope_id, effect)
select gen_random_uuid(), m.organization_id, r.id, 'USER', m.user_id, 'ORGANIZATION', m.organization_id, 'ALLOW'
from org_memberships m
join auth_roles r on r.system and r.code = m.role
where m.status = 'ACTIVE';

do $$
declare t text;
begin
    foreach t in array array['auth_permissions','auth_roles','auth_role_bindings'] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
