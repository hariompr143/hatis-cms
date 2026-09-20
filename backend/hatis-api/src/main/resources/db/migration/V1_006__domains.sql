-- ============================================================================
-- V1_006 — Domains and TLS certificates
--
-- The platform owns the lifecycle record; key material stays with the issuer
-- (cert-manager, ACM) or is stored encrypted for customer-managed certificates.
-- No column here ever holds a private key in plaintext.
-- ============================================================================

create table dom_domains (
    id                         uuid primary key,
    organization_id            uuid          not null,
    project_id                 uuid          not null,
    environment_id             uuid          not null,
    hostname                   citext        not null,
    -- Registrable domain, kept so registrations can be counted and rate limited
    -- per apex rather than per arbitrary subdomain.
    apex_domain                citext        not null,
    verification_method        varchar(20)   not null
                               check (verification_method in ('TXT_RECORD','CNAME_DELEGATION')),
    -- Published in public DNS by design, so not a secret - but single use and
    -- expiring, so it cannot be replayed to claim the hostname later.
    verification_token         varchar(128)  not null,
    verification_record_name   varchar(253)  not null,
    verified_at                timestamptz,
    expires_at                 timestamptz,
    certificate_secret_name    varchar(253),
    certificate_expires_at     timestamptz,
    dns_managed_by_platform    boolean       not null default false,
    force_https                boolean       not null default true,
    -- The lifecycle is explicit because DNS and TLS are asynchronous and partly
    -- outside the platform's control: a row existing does not mean a site works.
    status                     varchar(24)   not null default 'PENDING_VERIFICATION'
                               check (status in ('PENDING_VERIFICATION','PROVISIONING','ACTIVE',
                                                 'DEGRADED','FAILED','DELETED')),
    last_check_at              timestamptz,
    last_error                 varchar(512),
    created_by                 uuid,
    deleted_at                 timestamptz,
    created_at                 timestamptz   not null default now(),
    updated_at                 timestamptz   not null default now(),
    version                    bigint        not null default 0,
    -- Platform-wide, not per tenant: two customers must not be able to bind the
    -- same hostname and both receive its traffic.
    constraint uq_dom_domains_hostname unique (hostname)
);

create index ix_dom_domains_org on dom_domains (organization_id, project_id);
create index ix_dom_domains_status on dom_domains (status, expires_at);

comment on constraint uq_dom_domains_hostname on dom_domains is
    'A hostname is globally unique: two tenants must never be able to claim the same domain.';

create table dom_certificates (
    id                uuid primary key,
    organization_id   uuid          not null,
    domain_id         uuid          not null,
    -- ACME: issued through cert-manager. IMPORTED: supplied by the customer.
    provider          varchar(20)   not null check (provider in ('ACME','IMPORTED','AWS_ACM')),
    serial_number     varchar(128),
    status            varchar(20)   not null default 'PENDING'
                      check (status in ('PENDING','ISSUED','RENEWING','FAILED','EXPIRED','REVOKED')),
    not_before        timestamptz,
    not_after         timestamptz,
    renewal_attempts  integer       not null default 0,
    -- A reference into the secret store, or the name of the Kubernetes secret.
    -- Never the key itself.
    private_key_ref   varchar(512),
    last_error        varchar(1000),
    created_at        timestamptz   not null default now(),
    updated_at        timestamptz   not null default now(),
    version           bigint        not null default 0
);

create index ix_dom_certificates_expiry on dom_certificates (not_after) where status = 'ISSUED';
create index ix_dom_certificates_domain on dom_certificates (domain_id);

comment on column dom_certificates.private_key_ref is
    'Pointer to key material held by the secret store or by cert-manager. Certificate keys never appear in API responses or logs.';

do $$
declare t text;
begin
    foreach t in array array['dom_domains','dom_certificates'] loop
        execute format('create trigger trg_%s_touch before update on %I for each row execute function plat_touch_updated_at()', t, t);
    end loop;
end $$;
