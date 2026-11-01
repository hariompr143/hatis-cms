package com.hatis.platform.analytics.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.analytics.adapter.persistence.MetricStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules the platform-event rollup applies to one tenant.
 *
 * <p>Two properties carry the weight. The window is deleted before it is rewritten, so a
 * re-run recomputes rather than accumulates — the number in the metric is a count of
 * events, not of sweeps. And the day boundaries are computed by the database on both
 * sides, so counting and deleting can never split one day across two buckets. The
 * statements are asserted as text for the reason {@code MetricStoreTest} gives: the window
 * is built from constants of this class, and what must be pinned is that every
 * caller-supplied value is bound rather than concatenated.
 */
@DisplayName("Event rollup")
class EventRollupWorkTest {

    private final RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    private final UUID organizationId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-01T09:30:00Z");
    private final EventRollupWork work =
            new EventRollupWork(jdbcTemplate, new MetricStore(jdbcTemplate, new ObjectMapper()));

    @Test
    @DisplayName("the window is deleted before it is written, and the counts are bound, not built in")
    void theWindowIsRecomputedNotAccumulated() {
        jdbcTemplate.rows = List.of(
                new EventRollupWork.EventRollup("workflow.instance.started",
                        Instant.parse("2026-10-30T00:00:00Z"), 4),
                new EventRollupWork.EventRollup("workflow.instance.completed",
                        Instant.parse("2026-10-30T00:00:00Z"), 2));

        int written = work.roll(organizationId, now);

        assertThat(written).isEqualTo(2);

        assertThat(jdbcTemplate.statements.get(0))
                .as("the rollup's own rows go first; nothing else writes this key")
                .contains("delete from anl_metrics")
                .contains("interval '30 days'")
                .contains("date_trunc('day', ?::timestamptz)");
        assertThat(jdbcTemplate.arguments.get(0))
                .containsExactly(organizationId, EventRollupWork.METRIC_KEY,
                        Timestamp.from(now), Timestamp.from(now));

        assertThat(jdbcTemplate.statements.get(1))
                .as("the count comes from the outbox, grouped by the database's own day")
                .contains("from plat_outbox")
                .contains("date_trunc('day', occurred_at) as bucket")
                .contains("group by event_type, bucket")
                .contains("interval '30 days'");

        // Two appends through the metric store, each with the count bound as a value.
        assertThat(jdbcTemplate.statements.subList(2, 4))
                .allSatisfy(sql -> assertThat(sql).contains("insert into anl_metrics"));
        Object[] first = jdbcTemplate.arguments.get(2);
        assertThat(first[2]).isEqualTo(EventRollupWork.METRIC_KEY);
        assertThat(first[5]).isEqualTo("SUM");
        assertThat(first[6]).isEqualTo(new BigDecimal("4"));
        assertThat((String) first[7]).contains("\"eventType\":\"workflow.instance.started\"");
        assertThat(((Timestamp) first[8]).toInstant()).isEqualTo(Instant.parse("2026-10-30T00:00:00Z"));
        assertThat(jdbcTemplate.arguments.get(3)[6]).isEqualTo(new BigDecimal("2"));
    }

    @Test
    @DisplayName("a tenant with no events in the window writes no metric rows")
    void aSilentTenantWritesNothing() {
        jdbcTemplate.rows = List.of();

        assertThat(work.roll(organizationId, now)).isZero();
        assertThat(jdbcTemplate.statements)
                .as("the delete and the count still run; nothing is inserted — the recompute "
                        + "must clear the window's rows even when the count fell to zero")
                .hasSize(2)
                .anySatisfy(sql -> assertThat(sql).contains("delete from anl_metrics"))
                .noneSatisfy(sql -> assertThat(sql).contains("insert into anl_metrics"));
    }

    @Test
    @DisplayName("running the roll twice produces the same statements: recompute, never accumulate")
    void runningTwiceIsTheSameWork() {
        jdbcTemplate.rows = List.of(new EventRollupWork.EventRollup("asset.created",
                Instant.parse("2026-10-31T00:00:00Z"), 7));

        work.roll(organizationId, now);
        List<String> firstPass = List.copyOf(jdbcTemplate.statements);
        jdbcTemplate.statements.clear();
        work.roll(organizationId, now);

        assertThat(jdbcTemplate.statements).isEqualTo(firstPass);
    }

    /** Records every call and answers the rollup query from a canned list. */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private final List<String> statements = new ArrayList<>();
        private final List<Object[]> arguments = new ArrayList<>();
        private List<EventRollupWork.EventRollup> rows = List.of();

        @Override
        public int update(String sql, Object... args) {
            record(sql, args);
            return 1;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            record(sql, args);
            return (List<T>) rows;
        }

        private void record(String sql, Object[] args) {
            statements.add(sql);
            arguments.add(args);
        }
    }
}
