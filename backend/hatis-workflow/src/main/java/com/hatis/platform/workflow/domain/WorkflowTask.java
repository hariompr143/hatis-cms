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
 * Work waiting at one state for one assignee.
 *
 * <p>Maps {@code wf_tasks}. A task is created when an instance <em>arrives</em> at a state,
 * one per distinct assignee of the transitions leaving it: the seeded editorial workflow
 * creates a single task for {@code EDITOR} when it starts (the {@code submit} transition)
 * and a single task for {@code ORG_ADMIN} when it reaches {@code in_review} (both
 * {@code approve} and {@code reject}). Deduplicating by assignee is what stops a reviewer
 * with two ways forward from seeing the same item twice in their queue.
 *
 * <h2>What a task is not</h2>
 *
 * A task is not a lock. Two reviewers may both see the same {@code ORG_ADMIN} task, and the
 * engine resolves the race where it actually matters: in the transition, where the first
 * {@code approve} moves the instance and the second fails with a state conflict rather
 * than approving a decision twice. A claim would need a column to record who claimed it,
 * and {@code wf_tasks} has none — inventing one here would make {@code CLAIMED} a status
 * that says nothing. Task claiming arrives with the concurrency and SLA work in Phase 2.
 *
 * <h2>{@code EXPIRED} and {@code dueAt}</h2>
 *
 * {@code dueAt} is derived from an optional {@code slaHours} on the transition and is
 * returned to clients so an overdue approval is visible. Nothing expires a task in
 * Phase 1: automatic escalation on a missed deadline is Phase 2 (§19.3), and a scheduled
 * job that quietly cancelled work would be a product decision, not an implementation
 * detail.
 */
@Entity
@Table(name = "wf_tasks", indexes = {
        @Index(name = "ix_wf_tasks_open", columnList = "organization_id,status,assignee_type,assignee_id")
})
public class WorkflowTask extends TenantScopedEntity {

    public enum Status {
        OPEN,
        CLAIMED,
        COMPLETED,
        CANCELLED,
        EXPIRED
    }

    @Column(name = "instance_id", nullable = false, updatable = false)
    private UUID instanceId;

    @Column(name = "state", nullable = false, length = WorkflowDefinitionSpec.MAX_STATE_LENGTH, updatable = false)
    private String state;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_type", nullable = false, length = 20, updatable = false)
    private WorkflowDefinitionSpec.AssigneeType assigneeType;

    @Column(name = "assignee_id", length = WorkflowDefinitionSpec.MAX_ASSIGNEE_LENGTH, updatable = false)
    private String assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Column(name = "comment", length = 2000)
    private String comment;

    protected WorkflowTask() {
        super();
    }

    public WorkflowTask(UUID organizationId,
                        UUID instanceId,
                        String state,
                        WorkflowDefinitionSpec.Assignee assignee,
                        Instant dueAt) {
        super(organizationId);
        if (instanceId == null) {
            throw new IllegalArgumentException("a task must name its instance");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("a task must name the state it belongs to");
        }
        if (assignee == null) {
            throw new IllegalArgumentException("a task must name an assignee");
        }
        this.instanceId = instanceId;
        this.state = state;
        this.assigneeType = assignee.type();
        this.assigneeId = assignee.id();
        this.status = Status.OPEN;
        this.dueAt = dueAt;
    }

    /** Closes the task as done. {@code comment} is the reviewer's note, and is optional. */
    public void complete(UUID actorId, String comment) {
        if (status != Status.OPEN && status != Status.CLAIMED) {
            throw new PlatformExceptions.StateConflict("A task that is " + status + " cannot be completed");
        }
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
        this.completedBy = actorId;
        this.comment = comment == null || comment.isBlank() ? null : comment.trim();
    }

    /**
     * Closes the task because the instance moved on without it.
     *
     * <p>When one action resolves a state, every other task waiting at that state is
     * cancelled. Leaving them open would put work in someone's queue that can no longer be
     * done, which is how a task list stops being trusted.
     */
    public void cancel() {
        if (status == Status.COMPLETED || status == Status.CANCELLED) {
            throw new PlatformExceptions.StateConflict("A task that is " + status + " cannot be cancelled");
        }
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
    }

    /** True when this task can still be acted on. */
    public boolean isOpen() {
        return status == Status.OPEN || status == Status.CLAIMED;
    }

    public boolean isOverdue(Instant now) {
        return isOpen() && dueAt != null && dueAt.isBefore(now);
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public String getState() {
        return state;
    }

    public WorkflowDefinitionSpec.AssigneeType getAssigneeType() {
        return assigneeType;
    }

    public String getAssigneeId() {
        return assigneeId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public UUID getCompletedBy() {
        return completedBy;
    }

    public String getComment() {
        return comment;
    }
}
