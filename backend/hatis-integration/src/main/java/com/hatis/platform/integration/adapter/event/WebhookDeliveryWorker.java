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
 * <p><strong>Known defect.</strong> The tenant list is read from {@code org_organizations},
 * and this class's earlier javadoc claimed that table had no row level security. It does:
 * {@code org_organizations} is in the strict tenant table list in
 * {@code V1_013__row_level_security.sql} and carries {@code check (id = organization_id)},
 * so with no tenant bound this query returns <em>zero rows</em> and the sweep delivers
 * nothing. {@code OutboxRelayRlsIT#theOrganizationTableIsNotAReadableTenantDirectory}
 * pins that behaviour. Fixing it needs a tenant source that is not itself row level scoped
 * — a small unsecured directory or work-claim table — which is a schema decision rather
 * than a change to this class.
 *
 * <p>Iterating tenants every interval would also be the wrong shape at a few thousand
 * organizations even once the source is fixed, for the same reason.
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
        return jdbcTemplate.queryForList("select id from org_organizations", UUID.class);
    }
}
