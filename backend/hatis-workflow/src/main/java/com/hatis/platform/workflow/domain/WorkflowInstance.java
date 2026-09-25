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
 * outcome when it names one. {@code STUCK} is defined here because the column permits it
 * and a reader must be able to load any row the schema allows; nothing sets it in Phase 1,
 * because escalation on missed deadlines is Phase 2 ({@code docs/architecture/19}, §19.3).
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
    }

    /**
     * Moves the instance to {@code state}.
     *
     * @param terminalOutcome the outcome to record when {@code state} has no outgoing
     *                        transitions; {@code null} leaves the instance running
     */
    public void advanceTo(String state, WorkflowDefinitionSpec.Outcome terminalOutcome) {
        if (status != Status.RUNNING) {
            throw new PlatformExceptions.StateConflict(
                    "This workflow is " + status + " and cannot move to " + state);
        }
        this.currentState = state;
        if (terminalOutcome != null) {
            finish(terminalOutcome);
        }
    }

    /** Ends the instance without an approval decision: an administrator withdrew it. */
    public void cancel() {
        if (status != Status.RUNNING) {
            throw new PlatformExceptions.StateConflict("Only a running workflow can be cancelled");
        }
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
    }

    private void finish(WorkflowDefinitionSpec.Outcome outcome) {
        this.status = switch (outcome) {
            case COMPLETED -> Status.COMPLETED;
            case REJECTED -> Status.REJECTED;
            case CANCELLED -> Status.CANCELLED;
        };
        this.completedAt = Instant.now();
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
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
