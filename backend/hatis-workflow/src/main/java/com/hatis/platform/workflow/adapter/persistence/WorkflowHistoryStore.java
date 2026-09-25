package com.hatis.platform.workflow.adapter.persistence;

import com.hatis.platform.shared.id.Identifiers;
import com.hatis.platform.workflow.domain.WorkflowHistoryEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only access to {@code wf_history}.
 *
 * <p>Written with {@link JdbcTemplate} rather than as a JPA entity, and that is the point
 * rather than a shortcut. {@code V1_013} revokes {@code update} and {@code delete} on this
 * table from {@code hatis_app}, because an approval trail that can be quietly altered is
 * not an approval trail. Mapping it as an aggregate would offer
 * {@code repository.delete(...)} and {@code entity.setToState(...)} to every future caller;
 * this class offers one insert and one read, so the capability the database withholds is not
 * reachable from the code either.
 *
 * <p>It also avoids a schema compromise: {@code wf_history} has no {@code created_at},
 * {@code updated_at} or {@code version} column, so it could not extend
 * {@code TenantScopedEntity} without adding three columns that an immutable log has no use
 * for.
 *
 * <p>Inserts rely on the caller's transaction having bound the tenant — the same
 * {@code set_config('hatis.organization_id', …)} that {@code @TenantTransactional} applies —
 * because the row level security policy checks the organization on write as well as on read.
 */
@Repository
public class WorkflowHistoryStore {

    private static final String INSERT = """
            insert into wf_history (id, instance_id, organization_id, from_state, to_state,
                                    action, actor_id, reason, occurred_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_INSTANCE = """
            select id, instance_id, from_state, to_state, action, actor_id, reason, occurred_at
            from wf_history
            where instance_id = ? and organization_id = ?
            order by occurred_at asc, id asc
            """;

    private final JdbcTemplate jdbcTemplate;

    public WorkflowHistoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records one move.
     *
     * @param fromState the state left behind; {@code null} for the transition that starts the
     *                  instance, which is how a reader tells "started" from "moved"
     */
    public void append(UUID organizationId,
                       UUID instanceId,
                       String fromState,
                       String toState,
                       String action,
                       UUID actorId,
                       String reason) {
        jdbcTemplate.update(INSERT,
                Identifiers.newId(),
                instanceId,
                organizationId,
                fromState,
                toState,
                action,
                actorId,
                reason,
                Timestamp.from(Instant.now()));
    }

    /**
     * The history of one instance, oldest first.
     *
     * <p>The tiebreak on {@code id} exists because two rows written inside one transaction
     * can share an {@code occurred_at}: PostgreSQL's {@code now()} is the transaction's start
     * time. It is stable but arbitrary, and it is why this ordering is documented as
     * "insertion order within a transaction is not reproduced" rather than promised.
     */
    public List<WorkflowHistoryEntry> findByInstance(UUID organizationId, UUID instanceId) {
        return jdbcTemplate.query(SELECT_BY_INSTANCE, WorkflowHistoryStore::map, instanceId, organizationId);
    }

    private static WorkflowHistoryEntry map(ResultSet rows, int rowNumber) throws SQLException {
        return new WorkflowHistoryEntry(
                uuid(rows, "id"),
                uuid(rows, "instance_id"),
                rows.getString("from_state"),
                rows.getString("to_state"),
                rows.getString("action"),
                uuid(rows, "actor_id"),
                rows.getString("reason"),
                rows.getTimestamp("occurred_at").toInstant());
    }

    /** {@code getObject(name)} returns a {@link UUID} for a {@code uuid} column; this says so once. */
    private static UUID uuid(ResultSet rows, String column) throws SQLException {
        return (UUID) rows.getObject(column);
    }
}
