package com.hatis.platform.workflow.application;

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
 * Sweeps tenants and asks {@link WorkflowMaintenance} what the clock owes each one.
 *
 * <p>Two things are owed, and both are invisible without a sweep: a scheduled transition
 * declared as {@code autoAfterHours} does nothing while nobody looks, and a breached
 * deadline does not escalate itself. The interval is configured by
 * {@code hatis.workflow.sweep-interval} and defaults to one minute, which bounds how late
 * either one can be.
 *
 * <p>The sweep walks {@code plat_tenant_directory} rather than {@code org_organizations},
 * for the reason {@code AlertScanJob} gives in full: the tenant tables are behind row
 * level security and answer zero rows to an unbound query, which is how the outbox relay
 * once looked healthy while publishing nothing. One organization's failure is logged and
 * skipped, never allowed to stop the schedule or the organizations after it.
 *
 * <p>Only the worker role runs it — an API replica that also swept would fire every timer
 * twice.
 */
@Component
@ConditionalOnProperty(name = "hatis.role", havingValue = "worker", matchIfMissing = true)
public class WorkflowSweepJob {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSweepJob.class);

    private final WorkflowMaintenance maintenance;
    private final JdbcTemplate jdbcTemplate;
    private final Timer sweepDuration;

    public WorkflowSweepJob(WorkflowMaintenance maintenance, JdbcTemplate jdbcTemplate,
                            MeterRegistry meterRegistry) {
        this.maintenance = maintenance;
        this.jdbcTemplate = jdbcTemplate;
        this.sweepDuration = Timer.builder("hatis.workflow.sweep.duration").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${hatis.workflow.sweep-interval:60s}")
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
            log.error("Could not read the tenant directory; no workflows advanced this sweep", e);
            return;
        }
        int acted = 0;
        for (UUID organizationId : organizations) {
            try {
                acted += TenantContextHolder.callAs(tenantContextFor(organizationId),
                        () -> maintenance.runDue(organizationId, Instant.now()));
            } catch (RuntimeException e) {
                // One organization's failure must not stop the others, or the schedule.
                log.error("Workflow maintenance failed for organization {}", organizationId, e);
            }
        }
        if (acted > 0) {
            log.info("Workflow sweep acted {} time(s) across {} organizations",
                    acted, organizations.size());
        }
    }

    private static TenantContext tenantContextFor(UUID organizationId) {
        return TenantContext.of(organizationId, null, TenantContext.PrincipalType.SYSTEM);
    }
}
