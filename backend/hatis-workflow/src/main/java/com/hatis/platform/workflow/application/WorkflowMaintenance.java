package com.hatis.platform.workflow.application;

import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.tenant.TenantTransactional;
import com.hatis.platform.notification.application.NotificationService;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.workflow.adapter.persistence.WorkflowHistoryStore;
import com.hatis.platform.workflow.adapter.persistence.WorkflowRepositories;
import com.hatis.platform.workflow.domain.WorkflowDefinition;
import com.hatis.platform.workflow.domain.WorkflowDefinitionSpec;
import com.hatis.platform.workflow.domain.WorkflowInstance;
import com.hatis.platform.workflow.domain.WorkflowTask;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The clock's half of the workflow engine: scheduled transitions and SLA escalation.
 *
 * <p>Split from {@link WorkflowService} on purpose. Every method there begins by asking
 * <em>who</em> is acting and refusing anyone the definition does not assign; this class
 * acts as the platform itself, at a time no user chose. Keeping the two apart means the
 * permission rules cannot be quietly bypassed by calling the clock's entry point — the
 * clock has no permission to bypass, it has a schedule.
 *
 * <h2>Scheduled transitions</h2>
 *
 * A transition declaring {@code autoAfterHours} fires when the instance has been at its
 * {@code from} state for that many hours. The tasks waiting at that state are
 * <em>expired</em>, not cancelled: nobody withdrew the work and nobody did it — the
 * deadline the definition declared arrived first. The history row's actor is {@code null},
 * the same as the row that starts an instance: the clock did this, and recording a person
 * would be a lie the approval trail cannot afford.
 *
 * <h2>SLA escalation</h2>
 *
 * A task whose deadline ({@code dueAt}) has passed is escalated once: the original task is
 * marked, the instance becomes {@code STUCK}, and a second task is raised for the
 * {@code escalateTo} destination declared alongside the deadline. The original task stays
 * open — the assignee may still act; taking the work away from the person who owes it is
 * not escalation, it is confiscation. What the escalation <em>does</em> change is who may
 * resolve the state: {@link WorkflowService} lets an escalation task's assignee take any
 * transition from it. A task whose transition declares no {@code escalateTo} stays overdue
 * and visible in its queue: the deadline was declared, the definition chose no
 * destination, and inventing one here would escalate to somebody the customer never
 * named.
 *
 * <h2>Cost, stated rather than hidden</h2>
 *
 * Like {@code AlertScanJob}, the sweep walks every tenant on a fixed interval and most
 * organizations answer "nothing to do". The shared fix — a work-claim table — is not
 * built, and a second workaround here would make the eventual fix harder.
 */
@Service
public class WorkflowMaintenance {

    private static final List<WorkflowInstance.Status> IN_FLIGHT =
            List.of(WorkflowInstance.Status.RUNNING, WorkflowInstance.Status.STUCK);

    private final WorkflowRepositories.DefinitionRepository definitions;
    private final WorkflowRepositories.InstanceRepository instances;
    private final WorkflowRepositories.TaskRepository tasks;
    private final WorkflowDefinitionParser parser;
    private final WorkflowHistoryStore history;
    private final NotificationService notifications;
    private final EventPublisher events;
    private final AuditRecorder audit;

    public WorkflowMaintenance(WorkflowRepositories.DefinitionRepository definitions,
                               WorkflowRepositories.InstanceRepository instances,
                               WorkflowRepositories.TaskRepository tasks,
                               WorkflowDefinitionParser parser,
                               WorkflowHistoryStore history,
                               NotificationService notifications,
                               EventPublisher events,
                               AuditRecorder audit) {
        this.definitions = definitions;
        this.instances = instances;
        this.tasks = tasks;
        this.parser = parser;
        this.history = history;
        this.notifications = notifications;
        this.events = events;
        this.audit = audit;
    }

    /**
     * Everything the clock owes one organization at {@code now}: due scheduled transitions
     * and missed deadlines. Returns how many actions it took, for the sweep's log.
     *
     * <p>Timers run before escalation: when a scheduled transition moves the flow past a
     * state, its tasks expire and there is nothing left at that state to escalate — the
     * definition resolved the deadline itself.
     */
    @TenantTransactional
    public int runDue(UUID organizationId, Instant now) {
        int acted = fireScheduledTransitions(organizationId, now);
        acted += escalateOverdueTasks(organizationId, now);
        return acted;
    }

    // ------------------------------------------------------- scheduled transitions

    private int fireScheduledTransitions(UUID organizationId, Instant now) {
        int acted = 0;
        for (WorkflowInstance instance : instances.findByOrganizationIdAndStatusIn(
                organizationId, IN_FLIGHT)) {
            WorkflowDefinition definition = definitions
                    .findVisibleToTenant(instance.getDefinitionId(), organizationId)
                    .orElse(null);
            if (definition == null) {
                continue;
            }
            WorkflowDefinitionSpec spec = parser.parse(definition.getDefinition());
            WorkflowDefinitionSpec.Transition timer = spec
                    .scheduledFrom(instance.getCurrentState())
                    .orElse(null);
            if (timer == null) {
                continue;
            }
            Instant due = instance.getCurrentStateSince()
                    .plus(Duration.ofHours(timer.autoAfterHours()));
            if (due.isAfter(now)) {
                continue;
            }
            fireTimer(organizationId, instance, definition, spec, timer, now);
            acted++;
        }
        return acted;
    }

    private void fireTimer(UUID organizationId, WorkflowInstance instance,
                           WorkflowDefinition definition, WorkflowDefinitionSpec spec,
                           WorkflowDefinitionSpec.Transition timer, Instant now) {
        String fromState = instance.getCurrentState();
        for (WorkflowTask task : openTasks(organizationId, instance)) {
            if (!fromState.equals(task.getState())) {
                continue;
            }
            task.expire();
            tasks.save(task);
        }

        boolean terminal = spec.isTerminal(timer.to());
        instance.advanceTo(timer.to(), terminal
                ? (timer.outcome() == null
                        ? WorkflowDefinitionSpec.Outcome.COMPLETED
                        : timer.outcome())
                : null);
        instance = instances.save(instance);

        history.append(organizationId, instance.getId(), fromState, instance.getCurrentState(),
                timer.action(), null,
                "scheduled: fired " + timer.autoAfterHours() + " hours after entering '"
                        + fromState + "'");

        for (WorkflowTask task : openTasksAt(organizationId, instance, spec, now)) {
            notifyNamedAssignee(instance, task, "workflow.task.assigned",
                    "A workflow is waiting for you",
                    "A workflow on " + instance.getSubjectType() + " has reached the state '"
                            + task.getState() + "' and is assigned to you.");
        }

        events.publish(PlatformEvent.of(WorkflowService.eventTypeFor(instance), organizationId)
                .resource("workflow_instance", instance.getId())
                .data(WorkflowService.eventData(instance, definition, timer.action()))
                .build());
        audit.record(AuditRecord.builder("workflow.instance." + timer.action())
                .resource("workflow_instance", instance.getId())
                .metadata(Map.of("from", fromState,
                        "to", instance.getCurrentState(),
                        "status", instance.getStatus().name(),
                        "trigger", "scheduled"))
                .build());
    }

    // ------------------------------------------------------------- SLA escalation

    private int escalateOverdueTasks(UUID organizationId, Instant now) {
        int acted = 0;
        for (WorkflowTask task : tasks.findByOrganizationIdAndStatusAndDueAtBeforeAndEscalatedAtIsNull(
                organizationId, WorkflowTask.Status.OPEN, now)) {
            WorkflowInstance instance = instances
                    .findByIdAndOrganizationId(task.getInstanceId(), organizationId)
                    .orElse(null);
            if (instance == null || !instance.isRunning()
                    || !instance.getCurrentState().equals(task.getState())) {
                continue;
            }
            WorkflowDefinition definition = definitions
                    .findVisibleToTenant(instance.getDefinitionId(), organizationId)
                    .orElse(null);
            if (definition == null) {
                continue;
            }
            WorkflowDefinitionSpec spec = parser.parse(definition.getDefinition());
            WorkflowDefinitionSpec.Assignee destination = escalationDestination(spec, task);
            if (destination == null) {
                continue;
            }
            escalate(organizationId, instance, definition, task, destination, now);
            acted++;
        }
        return acted;
    }

    /**
     * Where an overdue task goes: the {@code escalateTo} of the transition that set its
     * deadline.
     *
     * <p>The deadline comes from the earliest {@code slaHours} among the assignee's
     * transitions leaving the state ({@link WorkflowDefinitionSpec#minSlaHours}), so the
     * rule attached to that same transition is the one that applies. A slower transition's
     * escalation destination does not fire early just because the faster one declared none.
     */
    private static WorkflowDefinitionSpec.Assignee escalationDestination(
            WorkflowDefinitionSpec spec, WorkflowTask task) {
        return spec.transitionsFrom(task.getState()).stream()
                .filter(transition -> transition.assignee().equals(task.getAssignee()))
                .filter(transition -> transition.slaHours() != null)
                .min(Comparator.comparingInt(WorkflowDefinitionSpec.Transition::slaHours))
                .map(WorkflowDefinitionSpec.Transition::escalateTo)
                .orElse(null);
    }

    private void escalate(UUID organizationId, WorkflowInstance instance,
                          WorkflowDefinition definition, WorkflowTask task,
                          WorkflowDefinitionSpec.Assignee destination, Instant now) {
        String state = task.getState();
        task.markEscalated();
        tasks.save(task);
        instance.markStuck();
        instance = instances.save(instance);

        boolean alreadyOpen = openTasks(organizationId, instance).stream()
                .filter(WorkflowTask::isEscalation)
                .anyMatch(open -> open.getState().equals(state)
                        && open.getAssignee().equals(destination));
        if (!alreadyOpen) {
            WorkflowTask escalationTask =
                    WorkflowTask.escalation(organizationId, instance.getId(), state, destination);
            tasks.save(escalationTask);
            notifyNamedAssignee(instance, escalationTask, "workflow.task.escalated",
                    "A workflow was escalated to you",
                    "A workflow on " + instance.getSubjectType() + " waited at '" + state
                            + "' past its deadline and has been escalated to you.");
        }
        if (task.getAssigneeType() == WorkflowDefinitionSpec.AssigneeType.USER) {
            notifyNamedAssignee(instance, task, "workflow.task.missed-deadline",
                    "Your workflow task missed its deadline",
                    "Your task at '" + state + "' on a workflow for " + instance.getSubjectType()
                            + " passed its deadline and has been escalated to "
                            + WorkflowService.describe(destination) + ".");
        }

        history.append(organizationId, instance.getId(), state, state, "sla_escalate", null,
                "task for " + WorkflowService.describe(task.getAssignee()) + " missed its deadline at "
                        + task.getDueAt() + "; escalated to " + WorkflowService.describe(destination));

        events.publish(PlatformEvent.of("workflow.task.escalated", organizationId)
                .resource("workflow_instance", instance.getId())
                .data(WorkflowService.eventData(instance, definition, "sla_escalate"))
                .build());
        audit.record(AuditRecord.builder("workflow.task.escalated")
                .resource("workflow_instance", instance.getId())
                .metadata(Map.of("state", state,
                        "assigneeType", task.getAssigneeType().name(),
                        "destinationType", destination.type().name()))
                .build());
    }

    // ----------------------------------------------------------------- task helpers

    private List<WorkflowTask> openTasks(UUID organizationId, WorkflowInstance instance) {
        return tasks.findByOrganizationIdAndInstanceIdAndStatusIn(organizationId, instance.getId(),
                List.of(WorkflowTask.Status.OPEN, WorkflowTask.Status.CLAIMED));
    }

    /** Opens the new state's tasks, with the deadlines the definition declares. */
    private List<WorkflowTask> openTasksAt(UUID organizationId, WorkflowInstance instance,
                                           WorkflowDefinitionSpec spec, Instant now) {
        String state = instance.getCurrentState();
        if (spec.isTerminal(state)) {
            return List.of();
        }
        List<WorkflowTask> opened = new ArrayList<>();
        for (WorkflowDefinitionSpec.Assignee assignee : spec.assigneesFor(state)) {
            Integer hours = spec.minSlaHours(state, assignee);
            Instant dueAt = hours == null ? null : now.plus(Duration.ofHours(hours));
            opened.add(tasks.save(new WorkflowTask(organizationId, instance.getId(), state,
                    assignee, dueAt)));
        }
        return opened;
    }

    private void notifyNamedAssignee(WorkflowInstance instance, WorkflowTask task,
                                     String templateKey, String subject, String body) {
        if (task.getAssigneeType() != WorkflowDefinitionSpec.AssigneeType.USER
                || task.getAssigneeId() == null) {
            return;
        }
        notifications.notify(new NotificationService.NotifyCommand(
                UUID.fromString(task.getAssigneeId()),
                Notification.Channel.IN_APP, templateKey, subject, body));
    }
}
