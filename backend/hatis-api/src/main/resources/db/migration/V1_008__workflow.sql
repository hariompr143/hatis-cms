-- ============================================================================
-- V1_008 — Workflow engine
--
-- Generic and reusable: the CMS uses it for content approval today; HR, CRM,
-- finance and deployment processes can use the same engine later without a
-- rewrite, because the engine knows about states and transitions, not content.
-- ============================================================================

create table wf_definitions (
    id                  uuid primary key,
    -- NULL marks a platform template available to every tenant. Tenant-owned
    -- definitions always carry their organization_id.
    organization_id     uuid,
    project_id          uuid,
    key                 varchar(120) not null,
    name                varchar(200) not null,
    version             integer      not null default 1,
    -- { states: [...], transitions: [{ from, to, action, guard, requiredRole }] }
    definition          jsonb        not null,
    status              varchar(20)  not null default 'DRAFT'
                        check (status in ('DRAFT','ACTIVE','RETIRED')),
    created_by          uuid,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now()
);

-- A plain unique constraint treats NULLs as distinct, which would let two templates
-- share a key. Coalescing makes catalogue rows comparable on every supported version.
create unique index uq_wf_definitions_key_version
    on wf_definitions (coalesce(organization_id, '00000000-0000-0000-0000-000000000000'),
                       coalesce(project_id, '00000000-0000-0000-0000-000000000000'),
                       key, version);

create table wf_instances (
    id                   uuid primary key,
    organization_id      uuid         not null,
    definition_id        uuid         not null,
    definition_version   integer      not null,
    subject_type         varchar(64)  not null,
    subject_id           uuid         not null,
    current_state        varchar(80)  not null,
    status               varchar(20)  not null default 'RUNNING'
                         check (status in ('RUNNING','COMPLETED','REJECTED','CANCELLED','STUCK')),
    started_at           timestamptz  not null default now(),
    completed_at         timestamptz,
    created_at           timestamptz  not null default now(),
    updated_at           timestamptz  not null default now(),
    version              bigint       not null default 0
);

create index ix_wf_instances_subject on wf_instances (organization_id, subject_type, subject_id);
create index ix_wf_instances_state   on wf_instances (organization_id, status, current_state);

create table wf_tasks (
    id               uuid primary key,
    instance_id      uuid         not null,
    organization_id  uuid         not null,
    state            varchar(80)  not null,
    -- A task is assigned to a role (any editor can review) or to a specific user.
    assignee_type    varchar(20)  not null check (assignee_type in ('ROLE','USER','ANY')),
    assignee_id      varchar(120),
    status           varchar(20)  not null default 'OPEN'
                     check (status in ('OPEN','CLAIMED','COMPLETED','CANCELLED','EXPIRED')),
    due_at           timestamptz,
    completed_at     timestamptz,
    completed_by     uuid,
    comment          varchar(2000),
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0
);

create index ix_wf_tasks_open on wf_tasks (organization_id, status, assignee_type, assignee_id);

-- Append-only history: who moved what, when, and why.
create table wf_history (
    id               uuid primary key,
    instance_id      uuid         not null,
    organization_id  uuid         not null,
    from_state       varchar(80),
    to_state         varchar(80)  not null,
    action           varchar(120) not null,
    actor_id         uuid,
    reason           varchar(2000),
    occurred_at      timestamptz  not null default now()
);

create index ix_wf_history_instance on wf_history (instance_id, occurred_at);

revoke update, delete on wf_history from public;

comment on table wf_history is 'Append-only. Workflow history is never rewritten, so an approval trail cannot be quietly altered.';

-- Default editorial workflow: created -> review -> approval -> published.
insert into wf_definitions (id, organization_id, project_id, key, name, version, definition, status)
values (gen_random_uuid(), null, null,
        'editorial_review', 'Editorial review', 1,
        '{
          "initialState": "draft",
          "states": ["draft","in_review","approved","published","rejected"],
          "transitions": [
            {"from":"draft","to":"in_review","action":"submit","assignee":{"type":"ROLE","id":"EDITOR"}},
            {"from":"in_review","to":"approved","action":"approve","assignee":{"type":"ROLE","id":"ORG_ADMIN"}},
            {"from":"in_review","to":"rejected","action":"reject","assignee":{"type":"ROLE","id":"ORG_ADMIN"}},
            {"from":"approved","to":"published","action":"publish","assignee":{"type":"ROLE","id":"ORG_ADMIN"}},
            {"from":"rejected","to":"draft","action":"rework","assignee":{"type":"ROLE","id":"EDITOR"}}
          ]
        }'::jsonb, 'ACTIVE');

do $$
declare t text;
begin
    foreach t in array array['wf_definitions','wf_instances','wf_tasks'] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
