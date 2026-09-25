package com.hatis.platform.analytics.adapter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.analytics.domain.MetricAggregation;
import com.hatis.platform.analytics.domain.MetricBucket;
import com.hatis.platform.analytics.domain.MetricPoint;
import com.hatis.platform.analytics.domain.MetricSample;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes and reads {@code anl_metrics} over JDBC.
 *
 * <h2>Why this is not a JPA repository</h2>
 *
 * The table is a fact table: it has no {@code updated_at} and no optimistic-lock column, so it
 * cannot extend {@code TenantScopedEntity}, and it should not. Metrics are appended and then
 * aggregated in SQL — {@code date_trunc}, {@code group by}, {@code sum} — and expressing that
 * through an entity graph would mean loading rows into the JVM to add them up.
 *
 * <h2>What is built from text and what is bound</h2>
 *
 * Every caller-supplied value is a bind parameter. The two fragments that are not —
 * the aggregate function and the bucket width — come from closed enums in the domain package
 * ({@link MetricAggregation}, {@link MetricBucket}) whose values are constants in this class,
 * so no request can put arbitrary text into the statement. That distinction is the whole
 * reason those two are enums rather than strings.
 *
 * <p>{@code dimension} is written and filtered through {@code ?::jsonb}, with the document
 * serialised here. A PostgreSQL cast is exact and cannot be substituted by a caller.
 */
@Repository
public class MetricStore {

    private static final String INSERT = """
            insert into anl_metrics (id, organization_id, key, name, unit, aggregation,
                                     value, dimension, bucket_start, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
            """;

    private static final String LATEST_AGGREGATION = """
            select aggregation
            from anl_metrics
            where organization_id = ? and key = ?
            order by bucket_start desc, created_at desc
            limit 1
            """;

    private static final String WINDOW_VALUE = """
            select %s as value
            from anl_metrics
            where organization_id = ? and key = ? and bucket_start >= ? and bucket_start < ?
            """;

    private static final String SERIES = """
            select date_trunc('%s', bucket_start) as bucket, %s as value
            from anl_metrics
            where organization_id = ? and key = ? and bucket_start >= ? and bucket_start < ?
              and (?::jsonb is null or dimension @> ?::jsonb)
            group by bucket
            order by bucket asc
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;

    public MetricStore(JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    /** Appends one measurement. The row level security check applies, so the tenant must be bound. */
    public void append(MetricSample sample) {
        jdbcTemplate.update(INSERT,
                Identifiers.newId(),
                sample.organizationId(),
                sample.key(),
                sample.name(),
                sample.unit(),
                sample.aggregation().name(),
                sample.value(),
                toJson(sample.dimension()),
                Timestamp.from(sample.bucketStart()));
    }

    /**
     * The aggregation the metric declares, taken from its most recent sample.
     *
     * <p>Alerts name a metric and a window but carry no aggregation of their own, so this is
     * what makes a gauge alert compare {@code last} rather than {@code sum}. Without it, an
     * alert on "storage used" would compare the sum of every sample in the window and fire
     * every time the collector ran.
     */
    public MetricAggregation preferredAggregation(UUID organizationId, String key) {
        List<String> stored = jdbcTemplate.queryForList(LATEST_AGGREGATION, String.class,
                organizationId, key);
        if (stored.isEmpty()) {
            return null;
        }
        try {
            return MetricAggregation.valueOf(stored.get(0));
        } catch (IllegalArgumentException e) {
            // A value the check constraint permits but this build does not know: treat it as
            // "no opinion" rather than failing the sweep, and let the caller pick.
            return null;
        }
    }

    /**
     * The single aggregate of one metric over a half-open window.
     *
     * <p>Returns {@code null} when the window holds no samples, which is deliberately different
     * from zero: an alert must not fire because a collector stopped reporting.
     */
    public BigDecimal valueInWindow(UUID organizationId, String key, MetricAggregation aggregation,
                                    Instant from, Instant to) {
        String sql = WINDOW_VALUE.formatted(aggregateExpression(aggregation));
        return jdbcTemplate.queryForObject(sql, (rows, rowNumber) -> rows.getBigDecimal("value"),
                organizationId, key, Timestamp.from(from), Timestamp.from(to));
    }

    /** A bucketed series for a chart. */
    public List<MetricPoint> series(UUID organizationId, String key, MetricAggregation aggregation,
                                    MetricBucket bucket, Instant from, Instant to,
                                    Map<String, Object> dimension) {
        String json = dimension == null || dimension.isEmpty() ? null : toJson(dimension);
        String sql = SERIES.formatted(bucket.truncUnit(), aggregateExpression(aggregation));
        return jdbcTemplate.query(sql,
                (rows, rowNumber) -> new MetricPoint(
                        rows.getTimestamp("bucket").toInstant(),
                        rows.getBigDecimal("value")),
                organizationId, key, Timestamp.from(from), Timestamp.from(to), json, json);
    }

    private static String aggregateExpression(MetricAggregation aggregation) {
        return switch (aggregation) {
            case SUM -> "sum(value)";
            case AVG -> "avg(value)";
            case MIN -> "min(value)";
            case MAX -> "max(value)";
            case COUNT -> "count(*)";
            // The last value written in the bucket, not the largest: for a gauge, the current
            // reading is the reading.
            case LAST -> "(array_agg(value order by bucket_start desc, created_at desc))[1]";
        };
    }

    private String toJson(Map<String, Object> dimension) {
        try {
            return mapper.writeValueAsString(dimension);
        } catch (Exception e) {
            throw new PlatformExceptions.Validation("The metric dimension could not be serialised",
                    Map.of("field", "dimension"));
        }
    }
}
