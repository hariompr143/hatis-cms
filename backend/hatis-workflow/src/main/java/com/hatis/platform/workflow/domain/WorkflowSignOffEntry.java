package com.hatis.platform.workflow.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One approval sign-off collected by a parallel gate.
 *
 * <p>Deliberately a plain record rather than an entity, for the reason
 * {@link WorkflowHistoryEntry} gives: {@code wf_signoffs} is append-only and
 * {@code V1_020} revokes {@code update} and {@code delete} on it. It is written and read
 * through {@code WorkflowSignOffStore}, which offers exactly the two operations the
 * table allows.
 *
 * <p>The uniqueness is on the database, not in the service: one row per
 * (instance, state, action, principal), so a principal who signs twice is recorded once.
 */
public record WorkflowSignOffEntry(
        UUID id,
        UUID instanceId,
        String state,
        String action,
        UUID actorId,
        String comment,
        Instant signedAt) {
}
