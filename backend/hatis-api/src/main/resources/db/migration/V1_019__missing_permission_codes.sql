-- ============================================================================
-- V1_019 — The content and deployment permission codes the code actually uses
--
-- Checking against a code the catalogue does not define denies everybody: the
-- authorization service matches the requested code against the permissions a role
-- was granted, so a mismatch is not a weaker check, it is a permanently closed
-- door. Two modules shipped with codes that were never seeded.
--
--   cms-cms          asked for 'content:read', 'content:write', 'content:publish',
--                    'content_type:read' and 'content_type:write'. The catalogue
--                    has cms:content:read, cms:content:write, cms:content:submit,
--                    cms:content:approve, cms:content:publish and cms:type:write —
--                    so every content endpoint answered 403 to every role,
--                    including the owner, whose '*' grant also matches by code.
--
--   hatis-deployment asked for 'application:read', 'application:write',
--                    'release:read' and 'release:write', none of which existed.
--
-- Only the codes that are genuinely absent are added. Nothing here is a rename:
-- a code that is already granted somewhere keeps its meaning, which matters
-- because auth_role_permissions rows reference the permission ids by code and a
-- tenant may have bound a custom role to one.
--
-- Added as a new migration rather than by editing V1_003 because V1_003 has
-- already run in CI, where a checksum change on an applied migration is a
-- failure rather than a fix.
-- ============================================================================

insert into auth_permissions (id, code, description, resource_type, action, classification) values
    (gen_random_uuid(), 'cms:type:read',     'Read content type definitions',  'content_type', 'read',    'INTERNAL'),
    (gen_random_uuid(), 'application:read',  'Read applications',              'application',  'read',    'INTERNAL'),
    (gen_random_uuid(), 'application:write', 'Create and update applications', 'application',  'write',   'INTERNAL'),
    (gen_random_uuid(), 'release:read',      'Read releases and their builds','release',      'read',    'INTERNAL'),
    (gen_random_uuid(), 'release:write',     'Create and cancel releases',     'release',      'write',   'RESTRICTED')
on conflict (code) do nothing;

-- Which system roles get them, and why.
--
--   cms:type:read     anyone who can already read content needs the definition to render it:
--                     ORG_ADMIN, DEVELOPER, EDITOR, ANALYST and VIEWER.
--   application:*     the deployment module's own resources. ORG_ADMIN administers them;
--                     DEVELOPER builds and deploys. VIEWER reads applications but cannot
--                     write one.
--   release:*         a release is produced by a pipeline and consumed by a deployment, so
--                     DEVELOPER writes them and ORG_ADMIN does everything. SECURITY_ADMIN,
--                     BILLING_ADMIN and ANALYST have no business in a release.
--
-- Grants are inserted only for roles that are still `system`, so a tenant that has redefined
-- a role is never silently re-granted a capability it removed. OWNER holds '*' and needs no
-- rows.
insert into auth_role_permissions (role_id, permission_id)
select r.id, p.id
from auth_roles r
join auth_permissions p on p.code in (
        'cms:type:read',
        'application:read', 'application:write', 'release:read', 'release:write')
join (values
        ('ORG_ADMIN', 'cms:type:read'),
        ('ORG_ADMIN', 'application:read'),
        ('ORG_ADMIN', 'application:write'),
        ('ORG_ADMIN', 'release:read'),
        ('ORG_ADMIN', 'release:write'),
        ('DEVELOPER', 'cms:type:read'),
        ('DEVELOPER', 'application:read'),
        ('DEVELOPER', 'application:write'),
        ('DEVELOPER', 'release:read'),
        ('DEVELOPER', 'release:write'),
        ('EDITOR',    'cms:type:read'),
        ('ANALYST',   'cms:type:read'),
        ('VIEWER',    'cms:type:read'),
        ('VIEWER',    'application:read'),
        ('VIEWER',    'release:read')
     ) as grant_to(role_code, permission_code)
  on grant_to.role_code = r.code and grant_to.permission_code = p.code
where r.system
on conflict (role_id, permission_id) do nothing;
