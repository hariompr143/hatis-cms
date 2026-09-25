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

import java.util.List;
import java.util.UUID;

/**
 * Sweeps tenants and asks {@link AlertWork} to evaluate each one's alerts.
 *
 * <p>The sweep exists because an alert that only fires when somebody calls an endpoint is not an
 * alert — it is a query with a schedule-shaped name. The interval is configured by
 * {@code hatis.analytics.alert-interval} and defaults to one minute, which bounds how late a
 * firing can be.
 *
 * <h2>Tenants come from the directory, not from {@code org_organizations}</h2>
 *
 * {@code org_organizations} is in the strict tenant list and carries
 * {@code check (id = organization_id)}; a query against it with no tenant bound returns zero
 * rows rather than an error. {@code plat_tenant_directory} exists precisely for this
 * ({@code V1_015}) and holds identifiers only. The same mistake in {@code OutboxRelay} meant no
 * event was ever published while every metric looked healthy, so it is not repeated here.
 *
 * <h2>Cost, stated rather than hidden</h2>
 *
 * This walks every tenant on a fixed interval, exactly as the outbox relay does, and so inherits
 * the same limitation: at a few thousand organizations most sweeps ask every one of them a
 * question whose answer is "nothing to do". The fix for both is one work-claim table listing
 * organizations with outstanding work. It is not built, and inventing a second, differently
 * shaped workaround here would make the eventual fix harder rather than easier.
 *
 * <p>Only the worker role runs it. An API replica that also swept would double every evaluation
 * and every notification, and the alert's own cooldown is the only thing that would hide it.
 */
@Component
@ConditionalOnProperty(name = "hatis.role", havingValue = "worker", matchIfMissing = true)
public class AlertScanJob {

    private static final Logger log = LoggerFactory.getLogger(AlertScanJob.class);

    private final AlertWork work;
    private final JdbcTemplate jdbcTemplate;
    private final Timer sweepDuration;

    public AlertScanJob(AlertWork work, JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.work = work;
        this.jdbcTemplate = jdbcTemplate;
        this.sweepDuration = Timer.builder("hatis.analytics.alert.sweep.duration").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${hatis.analytics.alert-interval:60s}")
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
            log.error("Could not read the tenant directory; no alerts evaluated this sweep", e);
            return;
        }
        int fired = 0;
        for (UUID organizationId : organizations) {
            try {
                fired += TenantContextHolder.callAs(tenantContextFor(organizationId),
                        () -> work.evaluate(organizationId));
            } catch (RuntimeException e) {
                // One organization's failure must not stop the others, and must not stop the
                // schedule.
                log.error("Alert evaluation failed for organization {}", organizationId, e);
            }
        }
        if (fired > 0) {
            log.info("Alert sweep fired {} alert(s) across {} organizations", fired, organizations.size());
        }
    }

    private static TenantContext tenantContextFor(UUID organizationId) {
        return TenantContext.of(organizationId, null, TenantContext.PrincipalType.SYSTEM);
    }
}
