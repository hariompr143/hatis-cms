package com.hatis.platform.workflow.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One recorded move of a workflow instance.
 *
 * <p>Deliberately a plain record rather than an entity: {@code wf_history} is append-only
 * and {@code V1_013} revokes {@code update} and {@code delete} on it from the application
 * role. There is no aggregate to load, mutate and save here, so mapping it as one would
 * offer two operations that the database refuses. It is written and read through
 * {@code WorkflowHistoryStore}, which offers exactly the two operations the table allows.
 */
public record WorkflowHistoryEntry(
        UUID id,
        UUID instanceId,
        String fromState,
        String toState,
        String action,
        UUID actorId,
        String reason,
        Instant occurredAt) {

    /** The move that started the instance: it had no previous state. */
    public boolean isStart() {
        return fromState == null;
    }
}
