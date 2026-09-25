package com.hatis.platform.workflow.application;

import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.notification.application.NotificationService;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import com.hatis.platform.workflow.adapter.persistence.WorkflowHistoryStore;
import com.hatis.platform.workflow.adapter.persistence.WorkflowRepositories;
import com.hatis.platform.workflow.domain.WorkflowDefinition;
import com.hatis.platform.workflow.domain.WorkflowDefinitionSpec;
import com.hatis.platform.workflow.domain.WorkflowHistoryEntry;
import com.hatis.platform.workflow.domain.WorkflowInstance;
import com.hatis.platform.workflow.domain.WorkflowTask;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs workflows: starts instances, moves them, and keeps their task queues honest.
 *
 * <h2>What the engine decides, and what it delegates</h2>
 *
 * A definition says which moves exist and who they belong to. An instance knows whether it is
 * still running. This service decides who may make a move right now, what work a move creates,
 * and what is recorded — and nothing else. It holds no knowledge of content, deployments or
 * invoices, which is what lets the same engine serve the CMS approval flow and a later
 * deployment process without being rewritten.
 *
 * <h2>Two questions per transition, because one is not enough</h2>
 *
 * Every move asks whether the caller holds {@code workflow:transition}, which is the crude
 * question an administrator can answer, and whether the caller is the assignee the definition
 * named, which is the business rule the definition exists to express. Dropping either one
 * breaks a real case: with only the permission, any editor could approve their own draft;
 * with only the assignee check, a user whose access was revoked but whose role binding has not
 * been cleaned up could still approve. A refusal is audited as {@code workflow.transition.denied}
 * with the required role named, because "who tried and could not" is the half of an approval
 * trail that gets asked about after an incident.
 *
 * <h2>Scope</h2>
 *
 * {@code wf_instances} has no project column, so authorisation is organization-scoped here.
 * That is a property of the schema, not an oversight: a binding at organization scope covers
 * everything inside the organization, and membership is what grants one, so a member's
 * bindings are exactly the ones that satisfy these checks.
 *
 * <h2>Notifications</h2>
 *
 * A task assigned to a named person raises an in-app notification for them. A task assigned
 * to a <em>role</em> deliberately does not: one role usually has many holders, a task is not a
 * message to each of them, and a queue that the holder can read through
 * {@code GET /v1/workflows/tasks} is what "waiting for an editor" actually means. Role-wide
 * fan-out belongs with notification preferences, which is Phase 2 work.
 */
@Service
public class WorkflowService {

    /** The definition used when a caller does not name one; seeded by {@code V1_008}. */
    public static final String DEFAULT_DEFINITION_KEY = "editorial_review";

    public static final String READ_PERMISSION = "workflow:read";
    public static final String START_PERMISSION = "workflow:start";
    public static final String TRANSITION_PERMISSION = "workflow:transition";
    public static final String ADMIN_PERMISSION = "workflow:admin";

    private static final List<WorkflowTask.Status> OPEN_STATUSES =
            List.of(WorkflowTask.Status.OPEN, WorkflowTask.Status.CLAIMED);

    private final WorkflowRepositories.DefinitionRepository definitions;
    private final WorkflowRepositories.InstanceRepository instances;
    private final WorkflowRepositories.TaskRepository tasks;
    private final WorkflowDefinitionParser parser;
    private final WorkflowHistoryStore history;
    private final AuthorizationService authorization;
    private final NotificationService notifications;
    private final EventPublisher events;
    private final AuditRecorder audit;

    public WorkflowService(WorkflowRepositories.DefinitionRepository definitions,
                           WorkflowRepositories.InstanceRepository instances,
                           WorkflowRepositories.TaskRepository tasks,
                           WorkflowDefinitionParser parser,
                           WorkflowHistoryStore history,
                           AuthorizationService authorization,
                           NotificationService notifications,
                           EventPublisher events,
                           AuditRecorder audit) {
        this.definitions = definitions;
        this.instances = instances;
        this.tasks = tasks;
        this.parser = parser;
        this.history = history;
        this.authorization = authorization;
        this.notifications = notifications;
        this.events = events;
        this.audit = audit;
    }

    // -------------------------------------------------------------- commands

    public record StartCommand(UUID projectId, String subjectType, UUID subjectId, String definitionKey) {
    }

    public record TransitionCommand(UUID instanceId, String action, String comment) {
    }

    // ------------------------------------------------------------- catalogue

    /**
     * Definitions this tenant may start: its own, plus the platform templates.
     *
     * <p>When {@code projectId} is given, definitions scoped to a different project are
     * filtered out. Templates carry no project, so they remain visible under every project —
     * which is what makes the seeded editorial flow usable without configuring anything.
     */
    @TenantTransactional(readOnly = true)
    public List<DefinitionView> listDefinitions(UUID projectId) {
        UUID organizationId = requireTenant(READ_PERMISSION);
        return definitions.findVisibleToTenant(organizationId).stream()
                .filter(definition -> projectId == null
                        || definition.getProjectId() == null
                        || projectId.equals(definition.getProjectId()))
                .map(this::toDefinitionView)
                .toList();
    }

    // --------------------------------------------------------------- running

    @TenantTransactional
    public InstanceDetail start(StartCommand command) {
        UUID organizationId = requireTenant(START_PERMISSION);
        UUID actorId = TenantContextHolder.require().principalId();
        String subjectType = command.subjectType() == null ? null : command.subjectType().trim();
        if (subjectType == null || subjectType.isBlank()) {
            throw new PlatformExceptions.Validation("subjectType is required", Map.of("field", "subjectType"));
        }
        if (command.subjectId() == null) {
            throw new PlatformExceptions.Validation("subjectId is required", Map.of("field", "subjectId"));
        }

        WorkflowDefinition definition = resolveDefinition(organizationId, command.projectId(),
                command.definitionKey());
        WorkflowDefinitionSpec spec = parser.parse(definition.getDefinition());

        // One running instance per subject. Without this, a retried submit or an impatient
        // double click forks one approval into two queues that can disagree about the answer.
        instances.findFirstByOrganizationIdAndSubjectTypeAndSubjectIdAndStatus(
                        organizationId, subjectType, command.subjectId(), WorkflowInstance.Status.RUNNING)
                .ifPresent(existing -> {
                    throw new PlatformExceptions.AlreadyExists(
                            "A workflow is already running for this " + subjectType);
                });

        WorkflowInstance instance = instances.save(new WorkflowInstance(organizationId, definition,
                spec.initialState(), subjectType, command.subjectId()));

        history.append(organizationId, instance.getId(), null, instance.getCurrentState(),
                "start", actorId, null);
        openTasks(organizationId, instance, spec);

        // A definition whose initial state has no outgoing transitions is satisfied the moment
        // it starts. Finishing it here rather than leaving it RUNNING keeps "running" meaning
        // "something can still happen".
        if (spec.isTerminal(spec.initialState())) {
            instance.advanceTo(spec.initialState(), WorkflowDefinitionSpec.Outcome.COMPLETED);
            instance = instances.save(instance);
        }

        events.publish(PlatformEvent.of("workflow.instance.started", organizationId)
                .resource("workflow_instance", instance.getId())
                .data(eventData(instance, definition, "start"))
                .build());
        audit.record(AuditRecord.builder("workflow.instance.started")
                .resource("workflow_instance", instance.getId())
                .metadata(Map.of("definition", definition.getKey(),
                        "subjectType", subjectType,
                        "subjectId", command.subjectId().toString()))
                .build());

        return detail(instance, definition, spec, organizationId);
    }

    @TenantTransactional
    public InstanceDetail transition(TransitionCommand command) {
        UUID organizationId = requireTenant(TRANSITION_PERMISSION);
        UUID actorId = TenantContextHolder.require().principalId();

        WorkflowInstance instance = requireInstance(organizationId, command.instanceId());
        if (!instance.isRunning()) {
            throw new PlatformExceptions.StateConflict(
                    "This workflow is " + instance.getStatus() + " and cannot move");
        }
        WorkflowDefinition definition = requireDefinition(organizationId, instance);
        WorkflowDefinitionSpec spec = parser.parse(definition.getDefinition());

        String action = command.action() == null ? "" : command.action().trim();
        WorkflowDefinitionSpec.Transition transition = spec
                .transition(instance.getCurrentState(), action)
                .orElseThrow(() -> new PlatformExceptions.StateConflict(
                        "No transition '" + action + "' leaves state '" + instance.getCurrentState() + "'"));

        if (!transition.assignee().permits(actorId,
                authorization.rolesAt(ScopeType.ORGANIZATION, organizationId))) {
            audit.record(AuditRecord.builder("workflow.transition.denied")
                    .resource("workflow_instance", instance.getId())
                    .result(AuditRecord.Result.DENIED)
                    .reason("'" + action + "' is assigned to " + describe(transition.assignee()))
                    .build());
            throw new PlatformExceptions.Forbidden(
                    "'" + action + "' is assigned to " + describe(transition.assignee()));
        }

        String fromState = instance.getCurrentState();
        closeTasksAt(organizationId, instance, transition.assignee(), actorId, command.comment());

        boolean terminal = spec.isTerminal(transition.to());
        instance.advanceTo(transition.to(), terminal
                ? (transition.outcome() == null
                        ? WorkflowDefinitionSpec.Outcome.COMPLETED
                        : transition.outcome())
                : null);
        instance = instances.save(instance);

        history.append(organizationId, instance.getId(), fromState, instance.getCurrentState(),
                action, actorId, command.comment());
        openTasks(organizationId, instance, spec);

        events.publish(PlatformEvent.of(eventTypeFor(instance), organizationId)
                .resource("workflow_instance", instance.getId())
                .data(eventData(instance, definition, action))
                .build());
        audit.record(AuditRecord.builder("workflow.instance." + action)
                .resource("workflow_instance", instance.getId())
                .metadata(Map.of("from", fromState, "to", instance.getCurrentState(),
                        "status", instance.getStatus().name()))
                .build());

        return detail(instance, definition, spec, organizationId);
    }

    /**
     * Withdraws a running workflow.
     *
     * <p>Requires {@code workflow:admin} rather than the transition permission, and that is a
     * deliberate distinction rather than a stricter random choice: cancelling removes work from
     * somebody else's queue without their agreement, which is an administrative act and not a
     * step in the flow.
     */
    @TenantTransactional
    public InstanceDetail cancel(UUID instanceId, String reason) {
        UUID organizationId = requireTenant(ADMIN_PERMISSION);
        UUID actorId = TenantContextHolder.require().principalId();

        WorkflowInstance instance = requireInstance(organizationId, instanceId);
        if (!instance.isRunning()) {
            throw new PlatformExceptions.StateConflict(
                    "This workflow is " + instance.getStatus() + " and cannot be cancelled");
        }
        WorkflowDefinition definition = requireDefinition(organizationId, instance);
        WorkflowDefinitionSpec spec = parser.parse(definition.getDefinition());

        for (WorkflowTask task : openTasks(organizationId, instance)) {
            task.cancel();
            tasks.save(task);
        }
        String state = instance.getCurrentState();
        instance.cancel();
        instance = instances.save(instance);

        history.append(organizationId, instance.getId(), state, state, "cancel", actorId, reason);

        events.publish(PlatformEvent.of("workflow.instance.cancelled", organizationId)
                .resource("workflow_instance", instance.getId())
                .data(eventData(instance, definition, "cancel"))
                .build());
        audit.record(AuditRecord.builder("workflow.instance.cancelled")
                .resource("workflow_instance", instance.getId())
                .reason(reason)
                .build());

        return detail(instance, definition, spec, organizationId);
    }

    // ----------------------------------------------------------------- reads

    @TenantTransactional(readOnly = true)
    public InstanceDetail instance(UUID instanceId) {
        UUID organizationId = requireTenant(READ_PERMISSION);
        WorkflowInstance instance = requireInstance(organizationId, instanceId);
        WorkflowDefinition definition = requireDefinition(organizationId, instance);
        return detail(instance, definition, parser.parse(definition.getDefinition()), organizationId);
    }

    /**
     * Lists instances, optionally of one subject or in one status.
     *
     * <p>Filtering by subject is how a caller finds the workflow governing one thing — the
     * question a content editor's screen asks — and it is answered by the index on
     * {@code (organization_id, subject_type, subject_id)} rather than by reading everything
     * and filtering in memory.
     */
    @TenantTransactional(readOnly = true)
    public PageResponse<InstanceSummary> list(String status, String subjectType, UUID subjectId,
                                              int page, int size) {
        UUID organizationId = requireTenant(READ_PERMISSION);
        var pageable = PageResponse.pageable(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
        var result = subjectType != null && !subjectType.isBlank() && subjectId != null
                ? instances.findByOrganizationIdAndSubjectTypeAndSubjectId(organizationId,
                        subjectType, subjectId, pageable)
                : status == null || status.isBlank()
                        ? instances.findByOrganizationId(organizationId, pageable)
                        : instances.findByOrganizationIdAndStatus(organizationId,
                                parseInstanceStatus(status), pageable);
        return PageResponse.from(result, this::toSummary);
    }

    /**
     * The caller's queue: tasks waiting on them by name, plus tasks waiting on a role they hold.
     *
     * <p>Assignment is evaluated against live role bindings rather than against a snapshot
     * taken when the task was created, so losing a role removes that work from the queue. That
     * is the behaviour that matters: a task list which survives a revocation keeps offering
     * access to somebody who no longer has any.
     */
    @TenantTransactional(readOnly = true)
    public PageResponse<TaskView> myTasks(int page, int size) {
        UUID organizationId = requireTenant(READ_PERMISSION);
        String principalId = String.valueOf(TenantContextHolder.require().principalId());
        List<String> roleCodes = authorization.rolesAt(ScopeType.ORGANIZATION, organizationId);
        var pageable = PageResponse.pageable(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));

        // Two queries rather than one with an `in` clause that is sometimes empty: an empty
        // collection in an `in` predicate is either an error or a silently different query,
        // depending on the dialect, and which one it is should not be decided here.
        var result = roleCodes.isEmpty()
                ? tasks.findAssignedToWithoutRoles(organizationId, OPEN_STATUSES,
                        WorkflowDefinitionSpec.AssigneeType.ANY,
                        WorkflowDefinitionSpec.AssigneeType.USER,
                        principalId, pageable)
                : tasks.findAssignedToWithRoles(organizationId, OPEN_STATUSES,
                        WorkflowDefinitionSpec.AssigneeType.ANY,
                        WorkflowDefinitionSpec.AssigneeType.USER,
                        WorkflowDefinitionSpec.AssigneeType.ROLE,
                        principalId, roleCodes, pageable);
        return PageResponse.from(result, this::toTaskView);
    }

    // --------------------------------------------------------------- helpers

    private UUID requireTenant(String permission) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require(permission, ScopeType.ORGANIZATION, organizationId);
        return organizationId;
    }

    private WorkflowInstance requireInstance(UUID organizationId, UUID instanceId) {
        return instances.findByIdAndOrganizationId(instanceId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Workflow instance", instanceId));
    }

    private WorkflowDefinition requireDefinition(UUID organizationId, WorkflowInstance instance) {
        return definitions.findVisibleToTenant(instance.getDefinitionId(), organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound(
                        "The workflow definition this instance follows is no longer available"));
    }

    /**
     * Resolves the definition to start: the project's own if one exists, then the tenant's,
     * then the platform template.
     *
     * <p>The order is the contract. A tenant that has configured its own editorial flow gets
     * it; a tenant that has not gets the platform's, which is why the template is seeded and
     * why it lives with a null {@code organization_id}. All three lookups are tenant-scoped
     * predicates against a table whose row level security widens reads to include the
     * template, so the template is readable without the policy being relaxed for anything else.
     */
    private WorkflowDefinition resolveDefinition(UUID organizationId, UUID projectId, String key) {
        String definitionKey = key == null || key.isBlank() ? DEFAULT_DEFINITION_KEY : key.trim();
        if (projectId != null) {
            List<WorkflowDefinition> scoped = definitions
                    .findByOrganizationIdAndProjectIdAndKeyAndStatusOrderByVersionDesc(
                            organizationId, projectId, definitionKey, WorkflowDefinition.Status.ACTIVE);
            if (!scoped.isEmpty()) {
                return scoped.get(0);
            }
        }
        List<WorkflowDefinition> tenantWide = definitions
                .findByOrganizationIdAndProjectIdIsNullAndKeyAndStatusOrderByVersionDesc(
                        organizationId, definitionKey, WorkflowDefinition.Status.ACTIVE);
        if (!tenantWide.isEmpty()) {
            return tenantWide.get(0);
        }
        List<WorkflowDefinition> template = definitions
                .findByOrganizationIdIsNullAndProjectIdIsNullAndKeyAndStatusOrderByVersionDesc(
                        definitionKey, WorkflowDefinition.Status.ACTIVE);
        if (!template.isEmpty()) {
            return template.get(0);
        }
        throw new PlatformExceptions.NotFound(
                "No active workflow definition named '" + definitionKey + "'");
    }

    /** Creates the task queue for whatever state the instance is now in. */
    private void openTasks(UUID organizationId, WorkflowInstance instance, WorkflowDefinitionSpec spec) {
        String state = instance.getCurrentState();
        if (spec.isTerminal(state)) {
            return;
        }
        for (WorkflowDefinitionSpec.Assignee assignee : spec.assigneesFor(state)) {
            WorkflowTask task = tasks.save(new WorkflowTask(organizationId, instance.getId(), state,
                    assignee, dueAtFor(spec, state, assignee)));
            notifyNamedAssignee(instance, task);
        }
    }

    /**
     * Completes the acting assignee's task and cancels the others waiting at that state.
     *
     * <p>Leaving the others open would put work in somebody's queue that can no longer be done,
     * which is how a task list stops being trusted.
     */
    private void closeTasksAt(UUID organizationId, WorkflowInstance instance,
                              WorkflowDefinitionSpec.Assignee acting, UUID actorId, String comment) {
        for (WorkflowTask task : openTasks(organizationId, instance)) {
            if (!instance.getCurrentState().equals(task.getState())) {
                continue;
            }
            if (task.getAssigneeType() == acting.type()
                    && Objects.equals(task.getAssigneeId(), acting.id())) {
                task.complete(actorId, comment);
            } else {
                task.cancel();
            }
            tasks.save(task);
        }
    }

    private List<WorkflowTask> openTasks(UUID organizationId, WorkflowInstance instance) {
        return tasks.findByOrganizationIdAndInstanceIdAndStatusIn(organizationId, instance.getId(),
                OPEN_STATUSES);
    }

    /** The earliest SLA among the transitions leaving {@code state} for this assignee. */
    private static Instant dueAtFor(WorkflowDefinitionSpec spec, String state,
                                    WorkflowDefinitionSpec.Assignee assignee) {
        Integer hours = spec.transitionsFrom(state).stream()
                .filter(transition -> transition.assignee().equals(assignee))
                .map(WorkflowDefinitionSpec.Transition::slaHours)
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);
        return hours == null ? null : Instant.now().plus(Duration.ofHours(hours));
    }

    /**
     * Tells a named assignee that work is waiting for them.
     *
     * <p>Only for a name. A role is not a distribution list, and a task assigned to one is
     * visible to its holders through the queue.
     */
    private void notifyNamedAssignee(WorkflowInstance instance, WorkflowTask task) {
        if (task.getAssigneeType() != WorkflowDefinitionSpec.AssigneeType.USER
                || task.getAssigneeId() == null) {
            return;
        }
        notifications.notify(new NotificationService.NotifyCommand(UUID.fromString(task.getAssigneeId()),
                Notification.Channel.IN_APP, "workflow.task.assigned", "A workflow is waiting for you",
                "A workflow on " + instance.getSubjectType() + " has reached the state '"
                        + task.getState() + "' and is assigned to you."));
    }

    private InstanceDetail detail(WorkflowInstance instance, WorkflowDefinition definition,
                                  WorkflowDefinitionSpec spec, UUID organizationId) {
        List<WorkflowTask> instanceTasks = tasks
                .findByOrganizationIdAndInstanceIdOrderByCreatedAtAsc(organizationId, instance.getId());
        List<String> actions = instance.isRunning()
                ? spec.transitionsFrom(instance.getCurrentState()).stream()
                        .map(WorkflowDefinitionSpec.Transition::action)
                        .toList()
                : List.of();
        return new InstanceDetail(toSummary(instance), definition.getKey(), definition.getVersion(),
                spec, instanceTasks.stream().map(this::toTaskView).toList(),
                history.findByInstance(organizationId, instance.getId()), actions);
    }

    private DefinitionView toDefinitionView(WorkflowDefinition definition) {
        return new DefinitionView(definition.getId(), definition.getKey(), definition.getName(),
                definition.getVersion(), definition.getStatus().name(), definition.isTemplate(),
                definition.getProjectId(), parser.parse(definition.getDefinition()));
    }

    private InstanceSummary toSummary(WorkflowInstance instance) {
        return new InstanceSummary(instance.getId(), instance.getSubjectType(), instance.getSubjectId(),
                instance.getCurrentState(), instance.getStatus().name(), instance.getDefinitionVersion(),
                instance.getStartedAt(), instance.getCompletedAt(), instance.getUpdatedAt());
    }

    private TaskView toTaskView(WorkflowTask task) {
        return new TaskView(task.getId(), task.getInstanceId(), task.getState(),
                task.getAssigneeType().name(), task.getAssigneeId(), task.getStatus().name(),
                task.getDueAt(), task.isOverdue(Instant.now()), task.getCompletedBy(),
                task.getComment(), task.getCreatedAt());
    }

    private static String describe(WorkflowDefinitionSpec.Assignee assignee) {
        return switch (assignee.type()) {
            case ANY -> "anyone holding the transition permission";
            case USER -> "one named user";
            case ROLE -> "role " + assignee.id();
        };
    }

    /**
     * The event a completed move publishes.
     *
     * <p>The name follows the instance's status rather than the action's name, because a
     * rejection is not always the end of a workflow: the seeded editorial flow lets an editor
     * rework a rejected item, so insisting on that step leaves the instance RUNNING and the
     * event is an ordinary transition. {@code workflow.instance.rejected} is published when
     * the rejection actually finishes the instance — either because the target state has no
     * outgoing transition, or because the transition declares {@code "outcome": "REJECTED"}.
     */
    private static String eventTypeFor(WorkflowInstance instance) {
        return switch (instance.getStatus()) {
            case COMPLETED -> "workflow.instance.completed";
            case REJECTED -> "workflow.instance.rejected";
            case CANCELLED -> "workflow.instance.cancelled";
            case RUNNING, STUCK -> "workflow.instance.transitioned";
        };
    }

    private static Map<String, Object> eventData(WorkflowInstance instance, WorkflowDefinition definition,
                                                 String action) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("definition", definition.getKey());
        data.put("definitionVersion", definition.getVersion());
        data.put("state", instance.getCurrentState());
        data.put("status", instance.getStatus().name());
        data.put("action", action);
        data.put("subjectType", instance.getSubjectType());
        data.put("subjectId", instance.getSubjectId().toString());
        return data;
    }

    private static WorkflowInstance.Status parseInstanceStatus(String value) {
        try {
            return WorkflowInstance.Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PlatformExceptions.Validation(
                    "Unknown workflow status '" + value + "'", Map.of("field", "status"));
        }
    }

    // ----------------------------------------------------------------- views

    public record DefinitionView(UUID id, String key, String name, int version, String status,
                                 boolean template, UUID projectId, WorkflowDefinitionSpec definition) {
    }

    public record InstanceSummary(UUID id, String subjectType, UUID subjectId, String currentState,
                                  String status, int definitionVersion, Instant startedAt,
                                  Instant completedAt, Instant updatedAt) {
    }

    public record TaskView(UUID id, UUID instanceId, String state, String assigneeType, String assigneeId,
                           String status, Instant dueAt, boolean overdue, UUID completedBy,
                           String comment, Instant createdAt) {
    }

    public record InstanceDetail(InstanceSummary instance, String definitionKey, int definitionVersion,
                                 WorkflowDefinitionSpec definition, List<TaskView> tasks,
                                 List<WorkflowHistoryEntry> history, List<String> availableActions) {
    }
}
