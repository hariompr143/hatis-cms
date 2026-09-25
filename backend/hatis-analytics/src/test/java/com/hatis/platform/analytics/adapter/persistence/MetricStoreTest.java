package com.hatis.platform.analytics.adapter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.analytics.domain.MetricAggregation;
import com.hatis.platform.analytics.domain.MetricBucket;
import com.hatis.platform.analytics.domain.MetricPoint;
import com.hatis.platform.analytics.domain.MetricSample;
import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fragment-building in {@code MetricStore}.
 *
 * <p>Asserting on generated SQL is unusual and is deliberate here. Two fragments are
 * concatenated rather than bound — the aggregate function and the {@code date_trunc} unit —
 * because PostgreSQL will not accept either as a bind parameter in every position. That makes
 * the closed enums the security boundary: the statements are built from constants, and nothing a
 * caller sends may reach the text. The tests below pin both halves: the statement contains the
 * enum's literal, and every caller-supplied value is a bound argument.
 *
 * <p>The template is a small recording subclass rather than a mock. {@code JdbcTemplate}'s
 * query methods are varargs, and matcher semantics for varargs are a subtlety that would make
 * these tests fail for a reason unrelated to what they are checking.
 */
@DisplayName("Metric store")
class MetricStoreTest {

    private static final String KEY = "cms.pages.published";

    private final RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    private final UUID organizationId = UUID.randomUUID();
    private final MetricStore store = new MetricStore(jdbcTemplate, new ObjectMapper());

    @Test
    @DisplayName("appending binds every value of the sample, in column order")
    void appendingBindsTheSample() {
        store.append(new MetricSample(organizationId, KEY, "Pages published", "count",
                MetricAggregation.SUM, new BigDecimal("12.5"), Map.of("project", "web"),
                Instant.parse("2026-09-25T10:03:47Z")));

        assertThat(jdbcTemplate.statement)
                .contains("insert into anl_metrics")
                .contains("?::jsonb");
        assertThat(jdbcTemplate.arguments).hasSize(9);
        assertThat(jdbcTemplate.arguments[1]).isEqualTo(organizationId);
        assertThat(jdbcTemplate.arguments[2]).isEqualTo(KEY);
        assertThat(jdbcTemplate.arguments[5]).isEqualTo("SUM");
        assertThat(jdbcTemplate.arguments[6]).isEqualTo(new BigDecimal("12.5"));
        assertThat((String) jdbcTemplate.arguments[7]).contains("\"project\":\"web\"");
        assertThat(((Timestamp) jdbcTemplate.arguments[8]).toInstant())
                .as("the sample's own bucket, truncated to the minute on the way in")
                .isEqualTo(Instant.parse("2026-09-25T10:03:00Z"));
    }

    @Test
    @DisplayName("a metric's declared aggregation is read from its newest sample")
    void theDeclaredAggregationIsTheNewestSamples() {
        jdbcTemplate.stringListResult = List.of("LAST");

        assertThat(store.preferredAggregation(organizationId, KEY)).isEqualTo(MetricAggregation.LAST);
        assertThat(jdbcTemplate.statement)
                .contains("order by bucket_start desc, created_at desc")
                .contains("limit 1");
        assertThat(jdbcTemplate.arguments).containsExactly(organizationId, KEY);
    }

    @Test
    @DisplayName("a metric with no samples, or a value this build does not know, has no opinion")
    void noOpinionIsNotAnError() {
        assertThat(store.preferredAggregation(organizationId, KEY)).isNull();

        // The check constraint permits values a later build may add; a sweep must not fail
        // because it met one, so the caller falls back to its own default.
        jdbcTemplate.stringListResult = List.of("MEDIAN");
        assertThat(store.preferredAggregation(organizationId, KEY)).isNull();
    }

    @Test
    @DisplayName("an empty window is null rather than zero, so a silent collector does not alert")
    void anEmptyWindowIsNull() {
        assertThat(store.valueInWindow(organizationId, KEY, MetricAggregation.SUM,
                Instant.parse("2026-09-25T00:00:00Z"), Instant.parse("2026-09-25T01:00:00Z")))
                .isNull();
    }

    @Test
    @DisplayName("a window aggregates with the metric's own function over a half-open range")
    void aWindowUsesTheMetricsFunction() {
        jdbcTemplate.scalar = new BigDecimal("150");

        assertThat(store.valueInWindow(organizationId, KEY, MetricAggregation.MAX,
                Instant.parse("2026-09-25T00:00:00Z"), Instant.parse("2026-09-25T01:00:00Z")))
                .isEqualByComparingTo("150");
        assertThat(jdbcTemplate.statement)
                .contains("max(value)")
                .contains("bucket_start >= ? and bucket_start < ?");
    }

    @Test
    @DisplayName("each aggregation and bucket contributes its own constant fragment")
    void fragmentsComeFromTheEnums() {
        series(MetricAggregation.COUNT, MetricBucket.DAY);
        assertThat(jdbcTemplate.statement).contains("date_trunc('day'").contains("count(*)");

        series(MetricAggregation.LAST, MetricBucket.MONTH);
        assertThat(jdbcTemplate.statement)
                .as("a gauge's value in a bucket is its newest reading, not its largest")
                .contains("date_trunc('month'")
                .contains("array_agg(value order by bucket_start desc, created_at desc)");

        series(MetricAggregation.AVG, MetricBucket.WEEK);
        assertThat(jdbcTemplate.statement).contains("date_trunc('week'").contains("avg(value)");
    }

    @Test
    @DisplayName("a dimension filter is bound as jsonb, never concatenated into the statement")
    void aDimensionIsBound() {
        series(MetricAggregation.SUM, MetricBucket.HOUR, Map.of("project", "' or 1=1 --"));

        assertThat(jdbcTemplate.statement).doesNotContain("or 1=1");
        assertThat(jdbcTemplate.arguments[4]).isEqualTo("{\"project\":\"' or 1=1 --\"}");
        assertThat(jdbcTemplate.arguments).hasSize(6);
    }

    @Test
    @DisplayName("a series maps the bucket start and the aggregated value")
    void aSeriesMapsItsRows() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        when(rows.getTimestamp("bucket")).thenReturn(Timestamp.from(Instant.parse("2026-09-25T00:00:00Z")));
        when(rows.getBigDecimal("value")).thenReturn(new BigDecimal("42"));
        jdbcTemplate.mapper = rowMapper -> {
            try {
                return rowMapper.mapRow(rows, 0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        List<MetricPoint> points = store.series(organizationId, KEY, MetricAggregation.SUM,
                MetricBucket.HOUR, Instant.parse("2026-09-25T00:00:00Z"),
                Instant.parse("2026-09-25T01:00:00Z"), null);

        assertThat(points).singleElement().satisfies(point -> {
            assertThat(point.bucketStart()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
            assertThat(point.value()).isEqualByComparingTo("42");
        });
    }

    @Test
    @DisplayName("a dimension that cannot be serialised is refused before the statement runs")
    void anUnserialisableDimensionIsRefused() {
        assertThatThrownBy(() -> store.series(organizationId, KEY, MetricAggregation.SUM,
                MetricBucket.HOUR, Instant.parse("2026-09-25T00:00:00Z"),
                Instant.parse("2026-09-25T01:00:00Z"), Map.of("loop", new Object())))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("dimension");

        assertThat(jdbcTemplate.statement).isNull();
    }

    private void series(MetricAggregation aggregation, MetricBucket bucket) {
        series(aggregation, bucket, null);
    }

    private void series(MetricAggregation aggregation, MetricBucket bucket,
                        Map<String, Object> dimension) {
        store.series(organizationId, KEY, aggregation, bucket, Instant.parse("2026-09-25T00:00:00Z"),
                Instant.parse("2026-09-25T01:00:00Z"), dimension);
    }

    /**
     * A {@link JdbcTemplate} that records the statement it was handed and the arguments it was
     * given, and returns canned results. Only the three methods {@link MetricStore} uses are
     * overridden.
     */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private String statement;
        private Object[] arguments;
        private List<String> stringListResult = List.of();
        private BigDecimal scalar;
        private Function<RowMapper<MetricPoint>, MetricPoint> mapper;

        @Override
        public int update(String sql, Object... args) {
            record(sql, args);
            return 1;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            record(sql, args);
            return (List<T>) stringListResult;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            record(sql, args);
            return (T) scalar;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            record(sql, args);
            if (mapper == null) {
                return List.of();
            }
            // The only production caller asks for MetricPoint rows, which is what the mapper
            // function was given to produce.
            return (List<T>) List.of(mapper.apply(safeCast(rowMapper)));
        }

        private void record(String sql, Object[] args) {
            this.statement = sql;
            this.arguments = args;
        }

        @SuppressWarnings("unchecked")
        private RowMapper<MetricPoint> safeCast(RowMapper<?> rowMapper) {
            return (RowMapper<MetricPoint>) rowMapper;
        }
    }
}
