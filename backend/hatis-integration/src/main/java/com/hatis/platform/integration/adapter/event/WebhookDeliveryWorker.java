package com.hatis.platform.integration.adapter.event;

import com.hatis.platform.integration.application.WebhookDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Drives outbound webhook delivery on the worker role.
 *
 * <h2>Why it iterates organizations</h2>
 *
 * A single query for "every delivery due, across all tenants" is not expressible here:
 * {@code int_webhook_deliveries} carries forced row level security, so a session with no
 * tenant set sees no rows at all. The work is therefore taken one tenant at a time, each
 * sweep running inside that tenant's context.
 *
 * <p>The tenant list comes from {@code plat_tenant_directory}, added by
 * {@code V1_015__platform_tenant_directory.sql}. It is <em>not</em> read from
 * {@code org_organizations}, and an earlier revision of this class did exactly that on the
 * stated grounds that the table had no row level security. It does: it is in the strict
 * tenant table list and carries {@code check (id = organization_id)}, so with no tenant
 * bound that query returned zero rows and the sweep delivered nothing.
 * {@code OutboxRelayRlsIT#theOrganizationTableIsStillTenantScoped} pins that.
 *
 * <p>The directory holds organization identifiers and nothing else, and is kept in sync by
 * a trigger on {@code org_organizations} so no code path has to remember to write it. It
 * exists precisely because widening reads on {@code org_organizations} itself would have
 * exposed {@code encryption_key_wrapped} to anything that set a flag.
 *
 * <p>Iterating every tenant each interval is still the wrong shape at a few thousand
 * organizations; a work-claim table listing only tenants with outstanding deliveries would
 * be the next step. That cost is stated rather than hidden.
 */
@Component
@ConditionalOnProperty(name = "hatis.role", havingValue = "worker", matchIfMissing = true)
public class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    /** Deliveries taken per tenant per sweep. Bounded so one tenant cannot starve the rest. */
    private static final int BATCH = 50;

    private final JdbcTemplate jdbcTemplate;
    private final WebhookDispatcher dispatcher;

    public WebhookDeliveryWorker(JdbcTemplate jdbcTemplate, WebhookDispatcher dispatcher) {
        this.jdbcTemplate = jdbcTemplate;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${hatis.events.webhook-interval:5s}")
    public void sweep() {
        for (UUID organizationId : organizations()) {
            try {
                dispatcher.deliverPending(organizationId, BATCH);
                dispatcher.retryDue(organizationId, Instant.now(), BATCH);
            } catch (RuntimeException e) {
                // One tenant's failure must not stop the others from being delivered to,
                // and must not stop the schedule: the deliveries stay queued and are
                // picked up by the next sweep.
                log.error("Webhook sweep failed for organization {}", organizationId, e);
            }
        }
    }

    private List<UUID> organizations() {
        // Not org_organizations: that table is row level scoped, and this query runs with
        // no tenant bound. The directory has no RLS by design and holds ids only.
        return jdbcTemplate.queryForList(
                "select organization_id from plat_tenant_directory order by created_at",
                UUID.class);
    }
}
