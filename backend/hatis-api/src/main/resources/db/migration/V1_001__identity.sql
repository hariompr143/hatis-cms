-- ============================================================================
-- V1_001 — Identity
--
-- Users are platform-wide, not tenant-scoped: one person may belong to several
-- organizations. Every credential is stored as a one-way hash or as ciphertext;
-- no plaintext secret ever lands in this schema.
-- ============================================================================

create table idp_users (
    id                    uuid primary key,
    email                 citext       not null,
    email_verified        boolean      not null default false,
    password_hash         varchar(100),
    password_updated_at   timestamptz,
    status                varchar(20)  not null default 'ACTIVE'
                          check (status in ('PENDING','ACTIVE','SUSPENDED','DELETED')),
    mfa_status            varchar(20)  not null default 'DISABLED'
                          check (mfa_status in ('DISABLED','ENROLLED','REQUIRED')),
    last_sign_in_at       timestamptz,
    failed_sign_in_count  integer      not null default 0,
    locked_until          timestamptz,
    credential_version    bigint       not null default 1,
    created_at            timestamptz  not null default now(),
    updated_at            timestamptz  not null default now(),
    version               bigint       not null default 0,
    deleted_at            timestamptz,
    constraint uq_idp_users_email unique (email)
);

comment on column idp_users.password_hash is 'bcrypt cost 12. Never logged, never returned by an API, never exported.';

create table idp_service_accounts (
    id               uuid primary key,
    organization_id  uuid          not null,
    name             varchar(120)  not null,
    description      varchar(2000),
    status           varchar(20)   not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    version          bigint        not null default 0
);

create index ix_idp_service_accounts_org on idp_service_accounts (organization_id);

create table idp_api_keys (
    id                  uuid primary key,
    organization_id     uuid          not null,
    name                varchar(120)  not null,
    key_hash            varchar(64)   not null,
    key_prefix          varchar(16)   not null,
    scopes              text          not null default '',
    service_account_id  uuid,
    last_used_at        timestamptz,
    expires_at          timestamptz,
    revoked_at          timestamptz,
    created_by          uuid,
    created_at          timestamptz   not null default now(),
    updated_at          timestamptz   not null default now(),
    version             bigint        not null default 0,
    constraint uq_idp_api_keys_hash unique (key_hash)
);

create index ix_idp_api_keys_org on idp_api_keys (organization_id);

comment on column idp_api_keys.key_hash is
    'SHA-256 of the plaintext key. The plaintext is shown once at creation and is not recoverable afterwards.';
comment on column idp_api_keys.key_prefix is
    'First characters of the key, kept only so a customer can recognise the key in the console and in audit records.';

create table idp_refresh_tokens (
    id               uuid primary key,
    user_id          uuid         not null,
    organization_id  uuid,
    token_hash       varchar(64)  not null,
    family_id        uuid         not null,
    expires_at       timestamptz  not null,
    revoked_at       timestamptz,
    replaced_by      uuid,
    user_agent       varchar(512),
    ip               varchar(64),
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    version          bigint       not null default 0,
    constraint uq_idp_refresh_token_hash unique (token_hash)
);

create index ix_idp_refresh_family on idp_refresh_tokens (family_id);
create index ix_idp_refresh_user   on idp_refresh_tokens (user_id) where revoked_at is null;

comment on table idp_refresh_tokens is
    'Rotating refresh tokens. Presenting an already-revoked token revokes the whole family: that is stolen-token detection.';

create table idp_mfa_enrolments (
    id                       uuid primary key,
    user_id                  uuid          not null,
    type                     varchar(16)   not null check (type in ('TOTP','WEBAUTHN')),
    secret_ciphertext        varchar(1024) not null,
    backup_codes_ciphertext  varchar(8192),
    verified_at              timestamptz,
    last_used_code           varchar(64),
    last_used_step           bigint,
    created_at               timestamptz   not null default now(),
    updated_at               timestamptz   not null default now(),
    version                  bigint        not null default 0
);

create index ix_idp_mfa_user on idp_mfa_enrolments (user_id);

comment on column idp_mfa_enrolments.secret_ciphertext is
    'TOTP shared secret encrypted with the tenant data key. There is no API that returns it after enrolment.';

create table idp_sign_in_events (
    id               uuid primary key,
    user_id          uuid,
    organization_id  uuid,
    email            citext,
    result           varchar(20)  not null check (result in ('SUCCESS','DENIED','LOCKED','MFA_REQUIRED')),
    reason           varchar(120),
    ip               varchar(64),
    user_agent       varchar(512),
    mfa_used         boolean      not null default false,
    occurred_at      timestamptz  not null default now()
);

create index ix_idp_sign_in_user  on idp_sign_in_events (user_id, occurred_at desc);
create index ix_idp_sign_in_email on idp_sign_in_events (email, occurred_at desc);

comment on table idp_sign_in_events is
    'Denormalised sign-in history for abuse detection. Retention is bounded; the audit trail is the durable record.';

do $$
declare t text;
begin
    foreach t in array array[
        'idp_users','idp_service_accounts','idp_api_keys','idp_refresh_tokens','idp_mfa_enrolments'
    ] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
