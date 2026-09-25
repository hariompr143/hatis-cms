package com.hatis.platform.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.notification.application.NotificationService;
import com.hatis.platform.notification.domain.Notification;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.workflow.adapter.persistence.WorkflowHistoryStore;
import com.hatis.platform.workflow.adapter.persistence.WorkflowRepositories;
import com.hatis.platform.workflow.domain.WorkflowDefinition;
import com.hatis.platform.workflow.domain.WorkflowDefinitionSpec;
import com.hatis.platform.workflow.domain.WorkflowFixtures;
import com.hatis.platform.workflow.domain.WorkflowInstance;
import com.hatis.platform.workflow.domain.WorkflowTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules the engine applies to a running workflow.
 *
 * <p>Two of these carry more weight than the rest. A transition needs the actor to hold the
 * role the definition assigns it, because otherwise any editor could approve their own work;
 * and moving a state closes the tasks waiting at it, because a queue that keeps an item
 * somebody can no longer act on stops being trusted. Both are asserted against what the
 * service does to its repositories rather than against a returned view, so a change that
 * keeps the response shape while dropping the rule cannot pass.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow service")
class WorkflowServiceTest {

    private static final String SUBJECT_TYPE = "content_item";

    /** The seeded editorial flow, reduced to the transitions these tests exercise. */
    private static final String EDITORIAL = """
            {
              "initialState": "draft",
              "states": ["draft", "in_review", "published", "rejected"],
              "transitions": [
                {"from":"draft","to":"in_review","action":"submit",
                 "assignee":{"type":"ROLE","id":"EDITOR"},"slaHours":24},
                {"from":"in_review","to":"published","action":"publish",
                 "assignee":{"type":"ROLE","id":"ORG_ADMIN"}},
                {"from":"in_review","to":"rejected","action":"reject",
                 "assignee":{"type":"ROLE","id":"ORG_ADMIN"}},
                {"from":"rejected","to":"draft","action":"rework",
                 "assignee":{"type":"ROLE","id":"EDITOR"}}
              ]
            }
            """;

    /** A definition that is satisfied the moment it starts: its initial state is terminal. */
    private static final String ALREADY_DONE = """
            {
              "initialState": "published",
              "states": ["published", "draft"],
              "transitions": [
                {"from":"draft","to":"published","action":"publish",
                 "assignee":{"type":"ROLE","id":"ORG_ADMIN"}}
              ]
            }
            """;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID principalId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();

    @Mock
    private WorkflowRepositories.DefinitionRepository definitions;
    @Mock
    private WorkflowRepositories.InstanceRepository instances;
    @Mock
    private WorkflowRepositories.TaskRepository tasks;
    @Mock
    private WorkflowHistoryStore history;
    @Mock
    private AuthorizationService authorization;
    @Mock
    private NotificationService notifications;
    @Mock
    private EventPublisher events;
    @Mock
    private AuditRecorder audit;

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(TenantContext.of(organizationId, principalId,
                TenantContext.PrincipalType.USER));
        service = new WorkflowService(definitions, instances, tasks,
                new WorkflowDefinitionParser(new ObjectMapper()), history, authorization,
                notifications, events, audit);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    // ------------------------------------------------------------------ start

    @Test
    @DisplayName("starting opens the initial state's task, records the start and announces it")
    void startingOpensTheInitialTask() {
        WorkflowDefinition definition = template(EDITORIAL);
        stubTemplate(definition);
        stubNoRunningInstance();
        stubPersistence();
        stubDetailReads();

        WorkflowService.InstanceDetail detail = service.start(
                new WorkflowService.StartCommand(null, SUBJECT_TYPE, subjectId, null));

        verify(authorization).require(WorkflowService.START_PERMISSION, ScopeType.ORGANIZATION,
                organizationId);

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(tasks).save(saved.capture());
        WorkflowTask task = saved.getValue();
        assertThat(task.getState()).isEqualTo("draft");
        assertThat(task.getAssigneeType()).isEqualTo(WorkflowDefinitionSpec.AssigneeType.ROLE);
        assertThat(task.getAssigneeId()).isEqualTo("EDITOR");
        assertThat(task.getStatus()).isEqualTo(WorkflowTask.Status.OPEN);
        assertThat(task.getDueAt())
                .as("the transition declares a 24 hour SLA")
                .isNotNull();

        verify(history).append(org.mockito.ArgumentMatchers.eq(organizationId),
                eq(detail.instance().id()), org.mockito.ArgumentMatchers.isNull(), eq("draft"),
                eq("start"), eq(principalId), org.mockito.ArgumentMatchers.isNull());

        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(events).publish(published.capture());
        assertThat(published.getValue().eventType()).isEqualTo("workflow.instance.started");
        assertThat(published.getValue().data()).containsEntry("subjectId", subjectId.toString());

        assertThat(detail.instance().status()).isEqualTo("RUNNING");
        assertThat(detail.instance().currentState()).isEqualTo("draft");
        assertThat(detail.availableActions()).containsExactly("submit");
        assertThat(detail.definitionKey()).isEqualTo("editorial_review");
    }

    @Test
    @DisplayName("a second start for the same subject is refused rather than forking the approval")
    void aSecondStartIsRefused() {
        WorkflowDefinition definition = template(EDITORIAL);
        stubTemplate(definition);
        when(instances.findFirstByOrganizationIdAndSubjectTypeAndSubjectIdAndStatus(
                organizationId, SUBJECT_TYPE, subjectId, WorkflowInstance.Status.RUNNING))
                .thenReturn(Optional.of(new WorkflowInstance(organizationId, definition, "draft",
                        SUBJECT_TYPE, subjectId)));

        assertThatThrownBy(() -> service.start(
                new WorkflowService.StartCommand(null, SUBJECT_TYPE, subjectId, null)))
                .isInstanceOf(PlatformExceptions.AlreadyExists.class)
                .hasMessageContaining("already running");

        verify(instances, never()).save(any(WorkflowInstance.class));
    }

    @Test
    @DisplayName("an unknown definition key is not found, and nothing is written")
    void anUnknownDefinitionIsNotFound() {
        when(definitions.findByOrganizationIdAndProjectIdAndKeyAndStatusOrderByVersionDesc(
                eq(organizationId), any(), eq("no_such_flow"), eq(WorkflowDefinition.Status.ACTIVE)))
                .thenReturn(List.of());
        when(definitions.findByOrganizationIdAndProjectIdIsNullAndKeyAndStatusOrderByVersionDesc(
                organizationId, "no_such_flow", WorkflowDefinition.Status.ACTIVE)).thenReturn(List.of());
        when(definitions.findByOrganizationIdIsNullAndProjectIdIsNullAndKeyAndStatusOrderByVersionDesc(
                "no_such_flow", WorkflowDefinition.Status.ACTIVE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.start(new WorkflowService.StartCommand(UUID.randomUUID(),
                SUBJECT_TYPE, subjectId, "no_such_flow")))
                .isInstanceOf(PlatformExceptions.NotFound.class)
                .hasMessageContaining("no_such_flow");

        verify(instances, never()).save(any(WorkflowInstance.class));
    }

    @Test
    @DisplayName("a definition whose initial state is terminal completes as it starts")
    void aTerminalInitialStateCompletesImmediately() {
        stubTemplate(template(ALREADY_DONE));
        stubNoRunningInstance();
        // Only the instance is saved: an initial state with no outgoing transitions opens no
        // task, which the assertion below then states rather than assumes.
        when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        stubDetailReads();

        WorkflowService.InstanceDetail detail = service.start(
                new WorkflowService.StartCommand(null, SUBJECT_TYPE, subjectId, null));

        assertThat(detail.instance().status()).isEqualTo("COMPLETED");
        assertThat(detail.instance().completedAt()).isNotNull();
        assertThat(detail.availableActions()).isEmpty();
        verify(tasks, never()).save(any(WorkflowTask.class));

        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(events, times(2)).publish(published.capture());
        assertThat(published.getAllValues())
                .extracting(PlatformEvent::eventType)
                .as("two facts: it started, and starting it finished it")
                .containsExactly("workflow.instance.started", "workflow.instance.completed");
    }

    @Test
    @DisplayName("a subject type is required")
    void aSubjectTypeIsRequired() {
        assertThatThrownBy(() -> service.start(
                new WorkflowService.StartCommand(null, " ", subjectId, null)))
                .isInstanceOf(PlatformExceptions.Validation.class);
    }

    // ------------------------------------------------------------- transition

    @Test
    @DisplayName("a transition by somebody the definition does not assign it to is refused and audited")
    void anIneligibleActorIsRefused() {
        WorkflowDefinition definition = template(EDITORIAL);
        WorkflowInstance instance = instanceAt(definition, "draft");
        stubInstanceLookup(instance, definition);
        when(authorization.rolesAt(ScopeType.ORGANIZATION, organizationId)).thenReturn(List.of("VIEWER"));

        assertThatThrownBy(() -> service.transition(new WorkflowService.TransitionCommand(
                instance.getId(), "submit", null)))
                .isInstanceOf(PlatformExceptions.Forbidden.class)
                .hasMessageContaining("role EDITOR");

        ArgumentCaptor<AuditRecord> recorded = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(recorded.capture());
        assertThat(recorded.getValue().action()).isEqualTo("workflow.transition.denied");
        assertThat(recorded.getValue().result()).isEqualTo(AuditRecord.Result.DENIED);
        assertThat(instance.getCurrentState()).isEqualTo("draft");
    }

    @Test
    @DisplayName("an eligible transition completes the acting task and opens the next queue")
    void anEligibleTransitionMovesTheInstance() {
        WorkflowDefinition definition = template(EDITORIAL);
        WorkflowInstance instance = instanceAt(definition, "draft");
        WorkflowTask editorTask = new WorkflowTask(organizationId, instance.getId(), "draft",
                WorkflowDefinitionSpec.Assignee.role("EDITOR"), null);
        stubInstanceLookup(instance, definition);
        when(authorization.rolesAt(ScopeType.ORGANIZATION, organizationId)).thenReturn(List.of("EDITOR"));
        when(tasks.findByOrganizationIdAndInstanceIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(editorTask));
        when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        when(tasks.save(any(WorkflowTask.class))).thenAnswer(call -> call.getArgument(0));
        stubDetailReads();

        WorkflowService.InstanceDetail detail = service.transition(
                new WorkflowService.TransitionCommand(instance.getId(), "submit", "ready for review"));

        assertThat(detail.instance().currentState()).isEqualTo("in_review");
        assertThat(detail.instance().status()).isEqualTo("RUNNING");
        assertThat(editorTask.getStatus()).isEqualTo(WorkflowTask.Status.COMPLETED);
        assertThat(editorTask.getCompletedBy()).isEqualTo(principalId);
        assertThat(editorTask.getComment()).isEqualTo("ready for review");

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(tasks, org.mockito.Mockito.times(2)).save(saved.capture());
        WorkflowTask opened = saved.getAllValues().get(1);
        assertThat(opened.getState()).isEqualTo("in_review");
        assertThat(opened.getAssigneeId()).isEqualTo("ORG_ADMIN");

        verify(history).append(org.mockito.ArgumentMatchers.eq(organizationId),
                eq(instance.getId()), eq("draft"), eq("in_review"), eq("submit"),
                eq(principalId), eq("ready for review"));
        assertThat(detail.availableActions()).containsExactlyInAnyOrder("publish", "reject");
    }

    @Test
    @DisplayName("a transition into a terminal state finishes the instance and opens no work")
    void aTerminalTransitionFinishesTheInstance() {
        WorkflowDefinition definition = template(EDITORIAL);
        WorkflowInstance instance = instanceAt(definition, "in_review");
        WorkflowTask adminTask = new WorkflowTask(organizationId, instance.getId(), "in_review",
                WorkflowDefinitionSpec.Assignee.role("ORG_ADMIN"), null);
        stubInstanceLookup(instance, definition);
        when(authorization.rolesAt(ScopeType.ORGANIZATION, organizationId)).thenReturn(List.of("ORG_ADMIN"));
        when(tasks.findByOrganizationIdAndInstanceIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(adminTask));
        when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        when(tasks.save(any(WorkflowTask.class))).thenAnswer(call -> call.getArgument(0));
        stubDetailReads();

        WorkflowService.InstanceDetail detail = service.transition(
                new WorkflowService.TransitionCommand(instance.getId(), "publish", null));

        assertThat(detail.instance().status()).isEqualTo("COMPLETED");
        assertThat(detail.instance().completedAt()).isNotNull();
        assertThat(detail.availableActions()).isEmpty();
        verify(tasks, org.mockito.Mockito.times(1)).save(any(WorkflowTask.class));

        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(events).publish(published.capture());
        assertThat(published.getValue().eventType()).isEqualTo("workflow.instance.completed");
        assertThat(published.getValue().data()).containsEntry("state", "published");
    }

    @Test
    @DisplayName("a rejected item stays open, because the seeded flow lets an editor rework it")
    void aRejectionThatCanBeReworkedKeepsRunning() {
        WorkflowDefinition definition = template(EDITORIAL);
        WorkflowInstance instance = instanceAt(definition, "in_review");
        stubInstanceLookup(instance, definition);
        when(authorization.rolesAt(ScopeType.ORGANIZATION, organizationId)).thenReturn(List.of("ORG_ADMIN"));
        when(tasks.findByOrganizationIdAndInstanceIdAndStatusIn(any(), any(), any())).thenReturn(List.of());
        when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        when(tasks.save(any(WorkflowTask.class))).thenAnswer(call -> call.getArgument(0));
        stubDetailReads();

        WorkflowService.InstanceDetail detail = service.transition(
                new WorkflowService.TransitionCommand(instance.getId(), "reject", "needs work"));

        assertThat(detail.instance().status()).isEqualTo("RUNNING");
        assertThat(detail.instance().currentState()).isEqualTo("rejected");
        assertThat(detail.availableActions()).containsExactly("rework");

        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(events).publish(published.capture());
        assertThat(published.getValue().eventType())
                .as("the instance is still RUNNING, so the rejection is a transition rather "
                        + "than the end of the workflow")
                .isEqualTo("workflow.instance.transitioned");
    }

    @Test
    @DisplayName("an action that does not leave the current state is a state conflict")
    void anUnknownActionIsAStateConflict() {
        WorkflowDefinition definition = template(EDITORIAL);
        WorkflowInstance instance = instanceAt(definition, "draft");
        stubInstanceLookup(instance, definition);

        // No role stub: 'publish' does not leave 'draft' at all, so the assignee is never
        // consulted. The refusal is about the definition, not about the caller.
        assertThatThrownBy(() -> service.transition(new WorkflowService.TransitionCommand(
                instance.getId(), "publish", null)))
                .isInstanceOf(PlatformExceptions.StateConflict.class)
                .hasMessageContaining("No transition 'publish' leaves state 'draft'");
    }

    @Test
    @DisplayName("a finished instance cannot be moved")
    void aFinishedInstanceCannotBeMoved() {
        WorkflowDefinition definition = template(EDITORIAL);
        WorkflowInstance instance = instanceAt(definition, "published");
        instance.advanceTo("published", WorkflowDefinitionSpec.Outcome.COMPLETED);
        when(instances.findByIdAndOrganizationId(instance.getId(), organizationId))
                .thenReturn(Optional.of(instance));

        assertThatThrownBy(() -> service.transition(new WorkflowService.TransitionCommand(
                instance.getId(), "submit", null)))
                .isInstanceOf(PlatformExceptions.StateConflict.class)
                .hasMessageContaining("COMPLETED");

        verify(events, never()).publish(any(PlatformEvent.class));
    }

    @Test
    @DisplayName("opening a task assigned to a named person notifies that person")
    void aNamedAssigneeIsNotified() {
        UUID reviewer = UUID.randomUUID();
        String definitionJson = """
                {
                  "initialState": "draft",
                  "states": ["draft", "review", "published"],
                  "transitions": [
                    {"from":"draft","to":"review","action":"submit",
                     "assignee":{"type":"ROLE","id":"EDITOR"}},
                    {"from":"review","to":"published","action":"approve",
                     "assignee":{"type":"USER","id":"%s"}}
                  ]
                }
                """.formatted(reviewer);
        WorkflowDefinition definition = template(definitionJson);
        WorkflowInstance instance = instanceAt(definition, "draft");
        stubInstanceLookup(instance, definition);
        when(authorization.rolesAt(ScopeType.ORGANIZATION, organizationId)).thenReturn(List.of("EDITOR"));
        when(tasks.findByOrganizationIdAndInstanceIdAndStatusIn(any(), any(), any())).thenReturn(List.of());
        when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        when(tasks.save(any(WorkflowTask.class))).thenAnswer(call -> call.getArgument(0));
        stubDetailReads();

        // The editor submits; arriving in "review" opens a task for the named reviewer, and
        // that is the moment the platform tells them.
        service.transition(new WorkflowService.TransitionCommand(instance.getId(), "submit", null));

        ArgumentCaptor<NotificationService.NotifyCommand> notified =
                ArgumentCaptor.forClass(NotificationService.NotifyCommand.class);
        verify(notifications).notify(notified.capture());
        assertThat(notified.getValue().userId()).isEqualTo(reviewer);
        assertThat(notified.getValue().channel()).isEqualTo(Notification.Channel.IN_APP);
        assertThat(notified.getValue().templateKey()).isEqualTo("workflow.task.assigned");
    }

    @Test
    @DisplayName("a step assigned to a role notifies nobody, because the queue is the notification")
    void aRoleAssigneeIsNotNotified() {
        WorkflowDefinition definition = template(EDITORIAL);
        WorkflowInstance instance = instanceAt(definition, "draft");
        stubInstanceLookup(instance, definition);
        when(authorization.rolesAt(ScopeType.ORGANIZATION, organizationId)).thenReturn(List.of("EDITOR"));
        when(tasks.findByOrganizationIdAndInstanceIdAndStatusIn(any(), any(), any())).thenReturn(List.of());
        when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        when(tasks.save(any(WorkflowTask.class))).thenAnswer(call -> call.getArgument(0));
        stubDetailReads();

        service.transition(new WorkflowService.TransitionCommand(instance.getId(), "submit", null));

        verify(notifications, never()).notify(any(NotificationService.NotifyCommand.class));
    }

    // ----------------------------------------------------------------- cancel

    @Test
    @DisplayName("cancelling needs the admin permission, closes open tasks and is recorded")
    void cancellingClosesOpenWork() {
        WorkflowDefinition definition = template(EDITORIAL);
        WorkflowInstance instance = instanceAt(definition, "in_review");
        WorkflowTask open = new WorkflowTask(organizationId, instance.getId(), "in_review",
                WorkflowDefinitionSpec.Assignee.role("ORG_ADMIN"), null);
        stubInstanceLookup(instance, definition);
        when(tasks.findByOrganizationIdAndInstanceIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(open));
        when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        when(tasks.save(any(WorkflowTask.class))).thenAnswer(call -> call.getArgument(0));
        stubDetailReads();

        WorkflowService.InstanceDetail detail = service.cancel(instance.getId(), "withdrawn by the author");

        verify(authorization).require(WorkflowService.ADMIN_PERMISSION, ScopeType.ORGANIZATION,
                organizationId);
        assertThat(detail.instance().status()).isEqualTo("CANCELLED");
        assertThat(open.getStatus()).isEqualTo(WorkflowTask.Status.CANCELLED);
        verify(history).append(org.mockito.ArgumentMatchers.eq(organizationId),
                eq(instance.getId()), eq("in_review"), eq("in_review"), eq("cancel"),
                eq(principalId), eq("withdrawn by the author"));

        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(events).publish(published.capture());
        assertThat(published.getValue().eventType()).isEqualTo("workflow.instance.cancelled");
    }

    @Test
    @DisplayName("a finished instance cannot be cancelled")
    void aFinishedInstanceCannotBeCancelled() {
        WorkflowDefinition definition = template(EDITORIAL);
        WorkflowInstance instance = instanceAt(definition, "published");
        instance.advanceTo("published", WorkflowDefinitionSpec.Outcome.COMPLETED);
        when(instances.findByIdAndOrganizationId(instance.getId(), organizationId))
                .thenReturn(Optional.of(instance));

        assertThatThrownBy(() -> service.cancel(instance.getId(), null))
                .isInstanceOf(PlatformExceptions.StateConflict.class)
                .hasMessageContaining("COMPLETED");
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("the catalogue marks which definitions are platform templates")
    void definitionsMarkTemplates() {
        WorkflowDefinition owned = WorkflowFixtures.definition(organizationId, "editorial_review", 2, EDITORIAL);
        WorkflowDefinition template = template(EDITORIAL);
        when(definitions.findVisibleToTenant(organizationId)).thenReturn(List.of(template, owned));

        List<WorkflowService.DefinitionView> views = service.listDefinitions(null);

        assertThat(views).hasSize(2);
        assertThat(views).extracting(WorkflowService.DefinitionView::template)
                .containsExactlyInAnyOrder(true, false);
        assertThat(views).extracting(WorkflowService.DefinitionView::version)
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("the catalogue hides definitions scoped to another project")
    void definitionsAreFilteredByProject() {
        UUID projectId = UUID.randomUUID();
        when(definitions.findVisibleToTenant(organizationId)).thenReturn(List.of(template(EDITORIAL)));

        assertThat(service.listDefinitions(projectId)).hasSize(1);
    }

    @Test
    @DisplayName("the caller's queue is looked up by name and by every role they hold")
    void theQueueUsesRolesWhenTheCallerHoldsThem() {
        when(authorization.rolesAt(ScopeType.ORGANIZATION, organizationId))
                .thenReturn(List.of("EDITOR", "ANALYST"));
        when(tasks.findAssignedToWithRoles(eq(organizationId), any(), any(), any(), any(),
                eq(principalId.toString()), org.mockito.ArgumentMatchers.argThat(
                        roles -> roles != null && roles.containsAll(List.of("EDITOR", "ANALYST"))),
                any(Pageable.class))).thenReturn(Page.empty());

        service.myTasks(0, 25);

        verify(tasks, never()).findAssignedToWithoutRoles(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a caller with no roles still sees tasks assigned to them by name or to anyone")
    void theQueueFallsBackWhenTheCallerHoldsNoRoles() {
        when(authorization.rolesAt(ScopeType.ORGANIZATION, organizationId)).thenReturn(List.of());
        when(tasks.findAssignedToWithoutRoles(eq(organizationId), any(), any(), any(),
                eq(principalId.toString()), any(Pageable.class))).thenReturn(Page.empty());

        service.myTasks(0, 25);

        verify(tasks, never()).findAssignedToWithRoles(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- helpers

    private static WorkflowDefinition template(String json) {
        return WorkflowFixtures.template(WorkflowService.DEFAULT_DEFINITION_KEY, 1, json);
    }

    private void stubTemplate(WorkflowDefinition definition) {
        when(definitions.findByOrganizationIdIsNullAndProjectIdIsNullAndKeyAndStatusOrderByVersionDesc(
                definition.getKey(), WorkflowDefinition.Status.ACTIVE)).thenReturn(List.of(definition));
    }

    private void stubNoRunningInstance() {
        when(instances.findFirstByOrganizationIdAndSubjectTypeAndSubjectIdAndStatus(
                organizationId, SUBJECT_TYPE, subjectId, WorkflowInstance.Status.RUNNING))
                .thenReturn(Optional.empty());
    }

    private void stubPersistence() {
        when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        when(tasks.save(any(WorkflowTask.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void stubDetailReads() {
        when(tasks.findByOrganizationIdAndInstanceIdOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());
        when(history.findByInstance(any(), any())).thenReturn(List.of());
    }

    private void stubInstanceLookup(WorkflowInstance instance, WorkflowDefinition definition) {
        when(instances.findByIdAndOrganizationId(instance.getId(), organizationId))
                .thenReturn(Optional.of(instance));
        when(definitions.findVisibleToTenant(definition.getId(), organizationId))
                .thenReturn(Optional.of(definition));
    }

    private WorkflowInstance instanceAt(WorkflowDefinition definition, String state) {
        return new WorkflowInstance(organizationId, definition, state, SUBJECT_TYPE, subjectId);
    }
}
