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
 * <p>The list of tenants comes from {@code org_organizations}, which is the one table
 * involved with no row level security — deliberately, since every tenant has to be able to
 * look others up for billing and support. Reading it with plain SQL rather than through the
 * organization context follows the same choice {@code TenantKeyService} makes: it keeps
 * this module from taking a compile-time dependency on another context's entities.
 *
 * <p>That is a full scan of tenants every interval, which is the wrong shape at a few
 * thousand organizations. The honest fix is a small unsecured work-claim table rather than
 * iterating tenants; this is correct and observable first, and the cost is stated rather
 * than hidden.
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
