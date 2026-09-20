-- ============================================================================
-- V1_012 — Audit trail
--
-- Append-only and tamper-evident. Each row carries the hash of the previous row
-- for its tenant, so silently removing or editing a record breaks the chain.
-- ============================================================================

create table aud_audit_logs (
    id               uuid primary key,
    organization_id  uuid,
    actor_type       varchar(32)  not null
                     check (actor_type in ('USER','SERVICE_ACCOUNT','API_KEY','PLATFORM_OPERATOR','SYSTEM')),
    actor_id         uuid,
    actor_email      varchar(320),
    action           varchar(120) not null,
    resource_type    varchar(80),
    resource_id      uuid,
    result           varchar(16)  not null default 'SUCCESS'
                     check (result in ('SUCCESS','DENIED','FAILED')),
    reason           varchar(512),
    ip               varchar(64),
    user_agent       varchar(512),
    correlation_id   varchar(64),
    metadata         jsonb,
    previous_hash    char(64)     not null,
    record_hash      char(64)     not null,
    sequence         bigint       not null,
    occurred_at      timestamptz  not null,
    constraint uq_aud_logs_chain unique (organization_id, sequence)
);

create index ix_aud_logs_org_time    on aud_audit_logs (organization_id, occurred_at desc);
create index ix_aud_logs_org_action  on aud_audit_logs (organization_id, action);
create index ix_aud_logs_resource    on aud_audit_logs (resource_type, resource_id);
create index ix_aud_logs_denied      on aud_audit_logs (organization_id, result, occurred_at desc)
    where result = 'DENIED';

comment on column aud_audit_logs.metadata is
    'Redacted context. Credential-shaped keys are replaced with [REDACTED] before the row is written.';
comment on column aud_audit_logs.record_hash is
    'sha256(previous_hash || canonical(record)). Removing or editing a row breaks the chain.';

-- The application role must not be able to rewrite history.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'hatis_app') then
        revoke update, delete on aud_audit_logs from hatis_app;
    end if;
end $$;

-- Verification helper: recomputes the chain and reports the first break.
create or replace function aud_verify_chain(p_organization uuid)
returns table (ok boolean, broken_at bigint) as $$
declare
    r record;
    expected char(64) := repeat('0', 64);
begin
    ok := true;
    broken_at := null;
    for r in select sequence, record_hash, previous_hash
             from aud_audit_logs
             where organization_id = p_organization
             order by sequence asc
    loop
        if r.previous_hash is distinct from expected then
            ok := false;
            broken_at := r.sequence;
            return next;
            return;
        end if;
        expected := r.record_hash;
    end loop;
    return next;
end;
$$ language plpgsql stable;

comment on function aud_verify_chain is
    'Walks a tenant''s audit chain and reports the first sequence whose previous_hash does not match. Used by the DR runbook and by periodic verification.';
