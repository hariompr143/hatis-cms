package com.hatis.platform.analytics.application;

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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sweeps tenants and asks {@link EventRollupWork} to recompute each one's platform-event
 * rollup.
 *
 * <p>The cadence is configured by {@code hatis.analytics.rollup-interval} and defaults to
 * one hour. Freshness that slow is deliberate: the rollup covers <em>closed</em> days, so
 * its output changes only when a day closes or a late event arrives — an hourly recompute
 * keeps both within an hour of true, and the work per sweep is bounded by
 * {@link EventRollupWork#WINDOW_DAYS} days of one tenant's events.
 *
 * <p>The sweep walks {@code plat_tenant_directory} rather than {@code org_organizations},
 * for the reason {@code AlertScanJob} gives in full: the tenant tables are behind row level
 * security and answer zero rows to an unbound query. One organization's failure is logged
 * and skipped, never allowed to stop the schedule or the organizations after it. Only the
 * worker role runs it — two replicas rolling the same tenant would delete each other's
 * fresh rows between the delete and the insert.
 */
@Component
@ConditionalOnProperty(name = "hatis.role", havingValue = "worker", matchIfMissing = true)
public class EventRollupJob {

    private static final Logger log = LoggerFactory.getLogger(EventRollupJob.class);

    private final EventRollupWork work;
    private final JdbcTemplate jdbcTemplate;
    private final Timer sweepDuration;

    public EventRollupJob(EventRollupWork work, JdbcTemplate jdbcTemplate,
                          MeterRegistry meterRegistry) {
        this.work = work;
        this.jdbcTemplate = jdbcTemplate;
        this.sweepDuration = Timer.builder("hatis.analytics.rollup.sweep.duration")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${hatis.analytics.rollup-interval:1h}")
    public void sweep() {
        sweepDuration.record(this::sweepOnce);
    }

    void sweepOnce() {
        List<UUID> organizations;
        try {
            organizations = jdbcTemplate.queryForList(
                    "select organization_id from plat_tenant_directory order by created_at",
                    UUID.class);
        } catch (RuntimeException e) {
            // The schedule must survive a failed directory read; the next sweep tries again.
            log.error("Could not read the tenant directory; no events rolled up this sweep", e);
            return;
        }
        int written = 0;
        for (UUID organizationId : organizations) {
            try {
                written += TenantContextHolder.callAs(tenantContextFor(organizationId),
                        () -> work.roll(organizationId, Instant.now()));
            } catch (RuntimeException e) {
                // One organization's failure must not stop the others, or the schedule.
                log.error("Event rollup failed for organization {}", organizationId, e);
            }
        }
        if (written > 0) {
            log.info("Event rollup rewrote {} metric row(s) across {} organizations",
                    written, organizations.size());
        }
    }

    private static TenantContext tenantContextFor(UUID organizationId) {
        return TenantContext.of(organizationId, null, TenantContext.PrincipalType.SYSTEM);
    }
}
