package com.hatis.platform.workflow.adapter.persistence;

import com.hatis.platform.shared.id.Identifiers;
import com.hatis.platform.workflow.domain.WorkflowSignOffEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only store for parallel-gate approvals ({@code wf_signoffs}).
 *
 * <p>The same shape as {@link WorkflowHistoryStore}: one insert and reads, because the
 * database withholds the other operations. The unique index on
 * {@code (instance_id, state, action, actor_id)} is what enforces "one approval per
 * principal" — a claim checked only in the service would race.
 */
@Repository
public class WorkflowSignOffStore {

    private static final String INSERT = """
            insert into wf_signoffs (id, instance_id, organization_id, state, action,
                                     actor_id, comment, signed_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_GATE = """
            select id, instance_id, state, action, actor_id, comment, signed_at
            from wf_signoffs
            where organization_id = ? and instance_id = ? and state = ? and action = ?
            order by signed_at asc, id asc
            """;

    private final JdbcTemplate jdbcTemplate;

    public WorkflowSignOffStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Records one approval. The caller has already checked the gate is still open. */
    public void record(UUID organizationId,
                       UUID instanceId,
                       String state,
                       String action,
                       UUID actorId,
                       String comment) {
        jdbcTemplate.update(INSERT,
                Identifiers.newId(),
                instanceId,
                organizationId,
                state,
                action,
                actorId,
                comment == null || comment.isBlank() ? null : comment.trim(),
                Timestamp.from(Instant.now()));
    }

    /**
     * The approvals collected for one gate, oldest first.
     *
     * <p>The tiebreak on {@code id} exists for the reason
     * {@link WorkflowHistoryStore#findByInstance} gives: rows written in one transaction
     * can share a {@code signed_at}.
     */
    public List<WorkflowSignOffEntry> findForGate(UUID organizationId, UUID instanceId,
                                                  String state, String action) {
        return jdbcTemplate.query(SELECT_BY_GATE, WorkflowSignOffStore::map,
                organizationId, instanceId, state, action);
    }

    private static WorkflowSignOffEntry map(ResultSet rows, int rowNumber) throws SQLException {
        return new WorkflowSignOffEntry(
                (UUID) rows.getObject("id"),
                (UUID) rows.getObject("instance_id"),
                rows.getString("state"),
                rows.getString("action"),
                (UUID) rows.getObject("actor_id"),
                rows.getString("comment"),
                rows.getTimestamp("signed_at").toInstant());
    }
}
