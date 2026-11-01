package com.hatis.platform.workflow.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One running workflow: which definition it follows, what it is about, where it is now.
 *
 * <p>Maps {@code wf_instances}. The definition is referenced by id <em>and</em> by the
 * version that was current when the instance started. That pair is what makes an approved
 * item reproducible: editing a definition later cannot change what an in-flight approval
 * was judged against, and the history of the instance still names the rules that were in
 * force.
 *
 * <h2>Terminal states, and why a rejection is not a failure to finish</h2>
 *
 * The engine decides the instance's status from the <em>state it arrives in</em>, not from
 * the action's name. A transition whose target still has outgoing transitions leaves the
 * instance {@code RUNNING} — which is why a rejection that can be reworked keeps running
 * in the seeded editorial workflow. A transition into a state with no outgoing transitions
 * ends the instance, with {@code COMPLETED} as the default and the transition's declared
 * outcome when it names one.
 *
 * <h2>{@code STUCK} means work is owed, not finished</h2>
 *
 * Escalation on a missed deadline (§19.3) marks the instance {@code STUCK}: the assignee
 * did not act in the time the definition allowed, and the escalation holder has been
 * pulled in to resolve it. Stuck is still in flight — the escalation holder can move it,
 * a scheduled transition can still fire, and an administrator can still withdraw it — so
 * {@link #isRunning()} covers {@code STUCK} as well. What separates {@code STUCK} from
 * {@code RUNNING} in the reports is that the deadline was missed, which is a fact worth
 * keeping after the flow moves on.
 */
@Entity
@Table(name = "wf_instances", indexes = {
        @Index(name = "ix_wf_instances_subject", columnList = "organization_id,subject_type,subject_id"),
        @Index(name = "ix_wf_instances_state", columnList = "organization_id,status,current_state")
})
public class WorkflowInstance extends TenantScopedEntity {

    public enum Status {
        RUNNING,
        COMPLETED,
        REJECTED,
        CANCELLED,
        STUCK
    }

    @Column(name = "definition_id", nullable = false, updatable = false)
    private UUID definitionId;

    @Column(name = "definition_version", nullable = false, updatable = false)
    private int definitionVersion;

    @Column(name = "subject_type", nullable = false, length = 64, updatable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "current_state", nullable = false, length = WorkflowDefinitionSpec.MAX_STATE_LENGTH)
    private String currentState;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    /**
     * When the instance arrived at its current state. Scheduled transitions fire at
     * {@code currentStateSince + autoAfterHours}, so this must move with the state rather
     * than be derived from history rows.
     */
    @Column(name = "current_state_since", nullable = false)
    private Instant currentStateSince;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected WorkflowInstance() {
        super();
    }

    public WorkflowInstance(UUID organizationId,
                            WorkflowDefinition definition,
                            String initialState,
                            String subjectType,
                            UUID subjectId) {
        super(organizationId);
        if (definition == null) {
            throw new IllegalArgumentException("a workflow instance must name its definition");
        }
        if (subjectType == null || subjectType.isBlank()) {
            throw new PlatformExceptions.Validation("subjectType is required", java.util.Map.of());
        }
        if (subjectId == null) {
            throw new PlatformExceptions.Validation("subjectId is required", java.util.Map.of());
        }
        this.definitionId = definition.getId();
        this.definitionVersion = definition.getVersion();
        this.subjectType = subjectType.trim();
        this.subjectId = subjectId;
        this.currentState = initialState;
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
        this.currentStateSince = this.startedAt;
    }

    /**
     * Moves the instance to {@code state}.
     *
     * <p>Moving resolves a stuck instance: whatever the deadline breach was, the flow has
     * now been moved past it, and the instance is running again until it finishes.
     *
     * @param terminalOutcome the outcome to record when {@code state} has no outgoing
     *                        transitions; {@code null} leaves the instance running
     */
    public void advanceTo(String state, WorkflowDefinitionSpec.Outcome terminalOutcome) {
        if (status != Status.RUNNING && status != Status.STUCK) {
            throw new PlatformExceptions.StateConflict(
                    "This workflow is " + status + " and cannot move to " + state);
        }
        this.currentState = state;
        this.currentStateSince = Instant.now();
        if (terminalOutcome != null) {
            finish(terminalOutcome);
        } else {
            this.status = Status.RUNNING;
        }
    }

    /** Ends the instance without an approval decision: an administrator withdrew it. */
    public void cancel() {
        if (status != Status.RUNNING && status != Status.STUCK) {
            throw new PlatformExceptions.StateConflict("Only a running workflow can be cancelled");
        }
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
    }

    /**
     * Records that a deadline was missed at the current state and escalation is under way.
     *
     * <p>Idempotent: several overdue tasks at the same state escalate against one instance,
     * and the second breach does not re-mark the first.
     */
    public void markStuck() {
        if (status == Status.RUNNING) {
            this.status = Status.STUCK;
        }
    }

    private void finish(WorkflowDefinitionSpec.Outcome outcome) {
        this.status = switch (outcome) {
            case COMPLETED -> Status.COMPLETED;
            case REJECTED -> Status.REJECTED;
            case CANCELLED -> Status.CANCELLED;
        };
        this.completedAt = Instant.now();
    }

    /**
     * Whether the instance can still be acted on.
     *
     * <p>{@code STUCK} counts: an escalation exists precisely so that somebody can move it,
     * and refusing the move would make the escalation pointless.
     */
    public boolean isRunning() {
        return status == Status.RUNNING || status == Status.STUCK;
    }

    public Instant getCurrentStateSince() {
        return currentStateSince;
    }

    public UUID getDefinitionId() {
        return definitionId;
    }

    public int getDefinitionVersion() {
        return definitionVersion;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getCurrentState() {
        return currentState;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
