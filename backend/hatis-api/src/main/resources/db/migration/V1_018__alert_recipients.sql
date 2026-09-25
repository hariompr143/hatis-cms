-- ============================================================================
-- V1_018 — Alert recipients
--
-- anl_alerts recorded the channels an alert delivers on (notify_channels) but not
-- who receives it. Without a recipient the only honest Phase 1 behaviour would be
-- to publish the alert event and deliver nothing in-app, which is what the
-- notification module's own contract calls out as the failure mode to avoid: a
-- notification raised for nobody is recorded as failed rather than faked.
--
-- Added here rather than by editing V1_011 for the same reason as V1_017: V1_011 has
-- run, and editing an applied migration fails the checksum check.
-- ============================================================================

alter table anl_alerts
    add column notify_user_ids uuid[] not null default '{}';

comment on column anl_alerts.notify_user_ids is
    'Users the alert notifies on every channel except WEBHOOK, which is delivered '
    'through the event itself. At least one recipient is required when the alert has '
    'an IN_APP or EMAIL channel; the domain enforces that, not this column.';

-- Alerts are swept per tenant with an index on the organization; the recipients are
-- read with the row, so no index on the array is warranted.
