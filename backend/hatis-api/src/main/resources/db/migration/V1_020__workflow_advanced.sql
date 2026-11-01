-- ============================================================================
-- V1_020 — Advanced workflow: parallel gates, scheduled transitions, SLA escalation
--
-- Phase 2 (§19.3). Three additions to the engine, each with the column it needs:
--
--   1. wf_signoffs collects the approvals a parallel gate requires before its
--      action fires. Append-only, like wf_history: an approval trail that can be
--      rewritten is not an approval trail.
--   2. wf_instances.current_state_since records when the instance arrived at its
--      current state. A scheduled transition fires at "arrival + autoAfterHours",
--      and without this column "when did it arrive" would be answered from history
--      rows whose ordering inside one transaction is documented as not reproduced.
--   3. wf_tasks gains the SLA-escalation bookkeeping: whether the task is an
--      escalation raised for a breached deadline (escalation), and when the
--      original task breached its deadline (escalated_at).
-- ============================================================================

-- 1. Parallel-gate sign-offs. One row per (gate, principal): the same principal
-- signing twice is one approval, whatever the column would otherwise permit.
create table wf_signoffs (
    id               uuid primary key,
    instance_id      uuid         not null,
    organization_id  uuid         not null,
    state            varchar(80)  not null,
    action           varchar(120) not null,
    actor_id         uuid         not null,
    comment          varchar(2000),
    signed_at        timestamptz  not null default now()
);

create unique index uq_wf_signoffs_gate on wf_signoffs (instance_id, state, action, actor_id);
create index ix_wf_signoffs_instance on wf_signoffs (organization_id, instance_id);

revoke update, delete on wf_signoffs from public;
revoke update, delete on wf_signoffs from hatis_app;

comment on table wf_signoffs is
    'Append-only. Parallel-gate approvals are recorded once per principal and never rewritten.';

-- 2. When the instance entered its current state.
alter table wf_instances add column current_state_since timestamptz not null default now();
update wf_instances set current_state_since = started_at;

-- 3. SLA escalation bookkeeping.
alter table wf_tasks add column escalation boolean not null default false;
alter table wf_tasks add column escalated_at timestamptz;

-- The sweep asks one question per interval - which open tasks have breached a
-- deadline and have not been escalated - and this partial index answers it
-- without walking completed history.
create index ix_wf_tasks_sla on wf_tasks (organization_id, due_at)
    where status = 'OPEN' and escalated_at is null;

-- 4. Row level security: wf_signoffs joins the strict tenant list.
alter table wf_signoffs enable row level security;
alter table wf_signoffs force row level security;

create policy wf_signoffs_tenant_isolation on wf_signoffs
    as permissive
    for all
    using (
        organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
    )
    with check (
        organization_id = nullif(current_setting('hatis.organization_id', true), '')::uuid
    );
