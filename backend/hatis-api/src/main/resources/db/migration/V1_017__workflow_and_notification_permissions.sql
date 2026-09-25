-- ============================================================================
-- V1_017 — Permissions for the workflow engine and notification delivery
--
-- The workflow engine and the notification module shipped after V1_003 wrote the
-- permission catalogue, so the capabilities they enforce —
-- workflow:read/start/transition/admin and notification:send — did not exist as
-- permissions. That is not a cosmetic gap: AuthorizationService denies a permission
-- that is not in the catalogue, so every workflow endpoint would have answered 403
-- to everybody including an owner, and the modules would have looked broken rather
-- than unconfigured.
--
-- Added here rather than by editing V1_003 because V1_003 has already run in CI and
-- a checksum change on an applied migration is a migration failure, not a fix.
-- ============================================================================

insert into auth_permissions (id, code, description, resource_type, action, classification) values
    (gen_random_uuid(), 'workflow:read',       'Read workflow definitions, instances and tasks', 'workflow', 'read',       'INTERNAL'),
    (gen_random_uuid(), 'workflow:start',      'Start a workflow on a subject',                  'workflow', 'start',      'INTERNAL'),
    (gen_random_uuid(), 'workflow:transition', 'Move a workflow along a transition',             'workflow', 'transition', 'INTERNAL'),
    (gen_random_uuid(), 'workflow:admin',      'Cancel or administer running workflows',         'workflow', 'admin',      'CONFIDENTIAL'),
    (gen_random_uuid(), 'notification:send',   'Notify another user of this organization',       'notification', 'send',   'CONFIDENTIAL')
on conflict (code) do nothing;

-- Which system roles get them, and why.
--
--   ORG_ADMIN  administers the workflow: it approves content in the seeded editorial
--              definition and can cancel a run, so it needs all four.
--   EDITOR     submits content for review and reworks a rejection — the two steps the
--              seeded definition assigns to that role — so it needs start and
--              transition. It deliberately does not get workflow:admin, just as the
--              catalogue does not give it cms:content:approve.
--   DEVELOPER  reads workflow state when wiring a deployment process to the engine,
--              but must not move somebody else's approval.
--   OWNER      holds every permission by definition ('*' in V1_003) and needs no rows.
--
-- The grants are inserted only for roles that are still `system`, so a tenant that has
-- redefined a role's name or description is not silently re-granted anything.
insert into auth_role_permissions (role_id, permission_id)
select r.id, p.id
from auth_roles r
join auth_permissions p on p.code in (
        'workflow:read', 'workflow:start', 'workflow:transition', 'workflow:admin', 'notification:send')
join (values
        ('ORG_ADMIN', 'workflow:read'),
        ('ORG_ADMIN', 'workflow:start'),
        ('ORG_ADMIN', 'workflow:transition'),
        ('ORG_ADMIN', 'workflow:admin'),
        ('ORG_ADMIN', 'notification:send'),
        ('EDITOR',    'workflow:read'),
        ('EDITOR',    'workflow:start'),
        ('EDITOR',    'workflow:transition'),
        ('DEVELOPER', 'workflow:read')
     ) as grant_to(role_code, permission_code)
  on grant_to.role_code = r.code and grant_to.permission_code = p.code
where r.system
on conflict (role_id, permission_id) do nothing;
