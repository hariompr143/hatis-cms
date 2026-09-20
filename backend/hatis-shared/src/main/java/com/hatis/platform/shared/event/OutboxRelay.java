package com.hatis.platform.shared.event;

import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Drains the transactional outbox into the configured {@link EventSink}s.
 *
 * <p>Runs on the worker role only. Each entry is published at least once; the
 * {@code eventId} in the envelope is what makes downstream consumers idempotent. A failed
 * entry backs off on its own rather than blocking the queue, so one poison message cannot
 * stall delivery of everything else.
 *
 * <h2>Why it iterates tenants instead of reading the table</h2>
 *
 * {@code plat_outbox} carries forced row level security and the migrations explicitly strip
 * {@code BYPASSRLS} from {@code hatis_app}. A query issued with no tenant bound therefore
 * returns <em>nothing</em> — not an error, an empty list. The previous version of this
 * class issued exactly that query and consequently published no event at all, while every
 * metric and log line continued to look healthy. That is the shape of failure this rewrite
 * is organised around: the database was enforcing isolation correctly, and the reader was
 * wrong.
 * {@code OutboxRelayRlsIT#anUnboundQueryStillSeesNoTenantOwnedRows} pins that behaviour, so
 * weakening the policy to make a cross-tenant scan work cannot pass unnoticed.
 *
 * <p>The work is therefore taken one tenant at a time. The tenant list comes from
 * {@code plat_tenant_directory} ({@code V1_015}), which holds organization identifiers and
 * nothing else and has no row level security, so it can be read unbound. Each tenant's
 * entries are then read and published inside that tenant's own context — which is the
 * access the row level security exists to grant, not to refuse.
 *
 * <p>Rows belonging to no tenant, i.e. platform-wide events, are a separate pass run with a
 * platform principal and so with no tenant bound. Those rows are readable and updatable
 * unbound but not insertable by a tenant; see {@code V1_016__outbox_platform_publish.sql}
 * for why the read and write policies differ, and what goes wrong if they do not.
 *
 * <h2>Cost, stated rather than hidden</h2>
 *
 * Walking every tenant on a two second interval is the wrong shape once there are a few
 * thousand of them: most sweeps run one empty query per organization that has no queued
 * work. The replacement is a work-claim table listing only organizations with outstanding
 * entries, populated by the same trigger that maintains the directory. It is not built yet,
 * and {@code plat_tenant_directory} is the honest interim.
 *
 * <h2>Where the transactions are</h2>
 *
 * Every database touch is delegated to {@link OutboxWork}, a separate bean, so that the
 * call crosses Spring's proxy and {@code @TenantTransactional} actually takes effect. This
 * class holds no transactional method at all: a bean calling its own transactional method
 * bypasses the proxy and runs with no transaction and no tenant setting, which is the
 * second half of the original defect.
 */
@Component
@ConditionalOnProperty(name = "hatis.role", havingValue = "worker", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxWork work;
    private final JdbcTemplate jdbcTemplate;
    private final Timer batchDuration;

    public OutboxRelay(OutboxWork work, JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.work = work;
        this.jdbcTemplate = jdbcTemplate;
        this.batchDuration = Timer.builder("hatis.events.relay.duration").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${hatis.events.relay-interval:2s}")
    public void drain() {
        batchDuration.record(this::drainBatch);
    }

    void drainBatch() {
        List<UUID> organizations;
        try {
            organizations = tenants();
        } catch (RuntimeException e) {
            // The schedule must survive a failed directory read; the entries stay queued
            // and the next sweep tries again.
            log.error("Could not read the tenant directory; no events drained this sweep", e);
            return;
        }
        for (UUID organizationId : organizations) {
            try {
                drainTenant(organizationId);
            } catch (RuntimeException e) {
                // One organization's failure must not stop the others being published, and
                // must not stop the schedule.
                log.error("Outbox drain failed for organization {}", organizationId, e);
            }
        }
        try {
            drainPlatformEvents();
        } catch (RuntimeException e) {
            log.error("Outbox drain failed for platform-wide events", e);
        }
    }

    /**
     * Publishes one organization's queued entries, inside that organization's context.
     *
     * <p>This method is deliberately not transactional. Wrapping a whole tenant in one
     * transaction would hold it open across every sink call, and the per-entry transaction
     * in {@link OutboxWork} would join it instead of being its own.
     */
    private void drainTenant(UUID organizationId) {
        TenantContextHolder.runAs(tenantContextFor(organizationId), () -> {
            for (UUID entryId : work.claimFor(organizationId)) {
                publish(entryId, "organization " + organizationId);
            }
        });
    }

    /** Publishes entries that belong to no organization, with no tenant bound. */
    private void drainPlatformEvents() {
        TenantContextHolder.runAs(TenantContext.system("outbox-relay"), () -> {
            for (UUID entryId : work.claimPlatformWide()) {
                publish(entryId, "the platform");
            }
        });
    }

    private void publish(UUID entryId, String scope) {
        try {
            work.publishOne(entryId);
        } catch (RuntimeException e) {
            // publishOne records a failed attempt for everything it can attribute. What
            // reaches here is a failure to record even that, in which case the entry keeps
            // its place in the queue and the next sweep retries it.
            log.error("Could not publish outbox entry {} for {}", entryId, scope, e);
        }
    }

    /**
     * Every organization that exists, in creation order.
     *
     * <p>Not {@code org_organizations}: that table is in the strict tenant list and carries
     * {@code check (id = organization_id)}, so this unbound query would return zero rows and
     * the relay would publish nothing — precisely the defect this class is written around.
     * The directory holds ids only, deliberately: widening reads on
     * {@code org_organizations} itself would have put {@code encryption_key_wrapped} behind
     * a flag any code path can set.
     */
    private List<UUID> tenants() {
        return jdbcTemplate.queryForList(
                "select organization_id from plat_tenant_directory order by created_at",
                UUID.class);
    }

    private static TenantContext tenantContextFor(UUID organizationId) {
        return TenantContext.of(organizationId, null, TenantContext.PrincipalType.SYSTEM);
    }
}
