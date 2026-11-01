package com.hatis.platform.analytics.application;

import com.hatis.platform.analytics.adapter.persistence.MetricStore;
import com.hatis.platform.analytics.domain.MetricAggregation;
import com.hatis.platform.analytics.domain.MetricSample;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rolls the platform's own event stream into tenant-visible metrics.
 *
 * <p>Every move the platform makes lands in {@code plat_outbox}: starts, transitions,
 * approvals, deployments, deliveries. Dashboards cannot query that table usefully — it is a
 * write path that grows without bound and whose rows carry payloads — so this job derives
 * the queryable shape: {@code platform.events}, one sample per closed day per event type,
 * written through the same {@link MetricStore} the metrics API writes through. Alerts then
 * work on platform activity with no new machinery: the metric declares {@code SUM}, and the
 * alert evaluator already knows what that means.
 *
 * <h2>Derived rows are recomputed, never accumulated</h2>
 *
 * Each sweep deletes the rollup's rows for its window and rewrites them from the outbox.
 * That is what makes the job safe to run twice, safe to crash halfway (the delete and the
 * writes share one transaction), and correct when an event arrives late: the number in the
 * metric is always a count of the outbox rows, not of the sweeps that have run. The
 * {@code platform.events} key namespace belongs to this job alone — nothing else writes it,
 * which is what makes deleting by key honest.
 *
 * <h2>The window, stated rather than hidden</h2>
 *
 * The rollup covers the trailing {@value #WINDOW_DAYS} closed days — yesterday and back.
 * Today is deliberately not written: a bucket still filling would go stale the moment the
 * sweep looked away, and a metric that is quietly wrong is worse than one that is missing.
 * The window is recomputed whole on every sweep, so a missed sweep costs freshness, never
 * correctness. Day boundaries come from the database ({@code date_trunc('day', ...)}) on
 * both the counting and the deleting side — computing them here in a different zone than
 * the session uses would split one day's counts across two buckets.
 */
@Service
public class EventRollupWork {

    public static final String METRIC_KEY = "platform.events";
    public static final String METRIC_NAME = "Platform events";
    public static final String METRIC_UNIT = "events";

    /** The trailing window of closed days the rollup covers. */
    public static final int WINDOW_DAYS = 30;

    private static final String DELETE_WINDOW = """
            delete from anl_metrics
            where organization_id = ? and key = ?
              and bucket_start >= date_trunc('day', ?::timestamptz) - interval '%d days'
              and bucket_start < date_trunc('day', ?::timestamptz)
            """.formatted(WINDOW_DAYS);

    private static final String ROLL_UP = """
            select event_type,
                   date_trunc('day', occurred_at) as bucket,
                   count(*) as events
            from plat_outbox
            where organization_id = ?
              and occurred_at >= date_trunc('day', ?::timestamptz) - interval '%d days'
              and occurred_at < date_trunc('day', ?::timestamptz)
            group by event_type, bucket
            order by bucket asc, event_type asc
            """.formatted(WINDOW_DAYS);

    private final JdbcTemplate jdbcTemplate;
    private final MetricStore metrics;

    public EventRollupWork(JdbcTemplate jdbcTemplate, MetricStore metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    /**
     * Recomputes one tenant's platform-event rollup for the trailing window. Returns how
     * many metric rows were written, for the sweep's log.
     */
    @TenantTransactional
    public int roll(UUID organizationId, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbcTemplate.update(DELETE_WINDOW, organizationId, METRIC_KEY, at, at);
        List<EventRollup> groups = jdbcTemplate.query(ROLL_UP, EventRollupWork::map,
                organizationId, at, at);
        for (EventRollup group : groups) {
            metrics.append(new MetricSample(organizationId, METRIC_KEY, METRIC_NAME, METRIC_UNIT,
                    MetricAggregation.SUM, BigDecimal.valueOf(group.events()),
                    Map.of("eventType", group.eventType()), group.bucket()));
        }
        return groups.size();
    }

    /** One (day, event type) count. Package-visible so the tests can state the mapping. */
    record EventRollup(String eventType, Instant bucket, long events) {
    }

    private static EventRollup map(ResultSet rows, int rowNumber) throws SQLException {
        return new EventRollup(
                rows.getString("event_type"),
                rows.getTimestamp("bucket").toInstant(),
                rows.getLong("events"));
    }
}
