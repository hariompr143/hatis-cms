package com.hatis.platform.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.notification.application.NotificationService;
import com.hatis.platform.shared.audit.AuditRecord;
import com.hatis.platform.shared.audit.AuditRecorder;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules the clock applies to a running workflow.
 *
 * <p>Two properties carry more weight than the rest. A scheduled transition expires the
 * tasks it moves past rather than completing somebody's work, and its history row names no
 * actor — the clock did it. And a missed deadline escalates <em>without confiscating the
 * work</em>: the assignee's task stays open beside the escalation task, so the assignee
 * can still act while the escalation holder resolves the state for them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow maintenance")
class WorkflowMaintenanceTest {

    private static final String SUBJECT_TYPE = "content_item";

    /** A flow whose {@code publish} is taken by the clock two hours after the state is entered. */
    private static final String TIMER = """
            {
              "initialState": "scheduled",
              "states": ["scheduled", "published"],
              "transitions": [
                {"from":"scheduled","to":"published","action":"publish",
                 "assignee":{"type":"ROLE","id":"ORG_ADMIN"},"autoAfterHours":2}
              ]
            }
            """;

    /**
     * A review state whose two assignees each have a deadline and share one escalation
     * destination.
     */
    private static final String ESCALATING = """
            {
              "initialState": "in_review",
              "states": ["in_review", "published", "rejected"],
              "transitions": [
                {"from":"in_review","to":"published","action":"approve",
                 "assignee":{"type":"ROLE","id":"ORG_ADMIN"},"slaHours":24,
                 "escalateTo":{"type":"USER","id":"%s"}},
                {"from":"in_review","to":"rejected","action":"reject",
                 "assignee":{"type":"ROLE","id":"EDITOR"},"slaHours":12,
                 "escalateTo":{"type":"USER","id":"%s"}}
              ]
            }
            """;

    /** The same, without an escalation destination: an overdue task stays where it is. */
    private static final String NO_DESTINATION = """
            {
              "initialState": "in_review",
              "states": ["in_review", "published"],
              "transitions": [
                {"from":"in_review","to":"published","action":"approve",
                 "assignee":{"type":"ROLE","id":"ORG_ADMIN"},"slaHours":24}
              ]
            }
            """;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();
    private final UUID escalationHolder = UUID.randomUUID();

    @Mock
    private WorkflowRepositories.DefinitionRepository definitions;
    @Mock
    private WorkflowRepositories.InstanceRepository instances;
    @Mock
    private WorkflowRepositories.TaskRepository tasks;
    @Mock
    private WorkflowHistoryStore history;
    @Mock
    private NotificationService notifications;
    @Mock
    private EventPublisher events;
    @Mock
    private AuditRecorder audit;

    private WorkflowMaintenance maintenance;

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(TenantContext.of(organizationId, null,
                TenantContext.PrincipalType.SYSTEM));
        maintenance = new WorkflowMaintenance(definitions, instances, tasks,
                new WorkflowDefinitionParser(new ObjectMapper()), history, notifications,
                events, audit);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    // ------------------------------------------------------ scheduled transitions

    @Test
    @DisplayName("a scheduled transition fires after its hours pass, expiring the tasks it moves past")
    void aDueScheduledTransitionFires() {
        WorkflowDefinition definition = template(TIMER);
        WorkflowInstance instance = instanceAt(definition, "scheduled");
        WorkflowTask waiting = new WorkflowTask(organizationId, instance.getId(), "scheduled",
                WorkflowDefinitionSpec.Assignee.role("ORG_ADMIN"), null);
        stubTimerLookup(instance, definition, waiting);

        int acted = maintenance.runDue(organizationId, Instant.now().plus(Duration.ofHours(3)));

        assertThat(acted).isEqualTo(1);
        assertThat(instance.getCurrentState()).isEqualTo("published");
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstance.Status.COMPLETED);
        assertThat(waiting.getStatus())
                .as("nobody did this work; the deadline the definition declared arrived first")
                .isEqualTo(WorkflowTask.Status.EXPIRED);

        ArgumentCaptor<AuditRecord> recorded = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(recorded.capture());
        assertThat(recorded.getValue().action()).isEqualTo("workflow.instance.publish");
        assertThat(recorded.getValue().metadata()).containsEntry("trigger", "scheduled");

        verify(history).append(org.mockito.ArgumentMatchers.eq(organizationId),
                org.mockito.ArgumentMatchers.eq(instance.getId()),
                org.mockito.ArgumentMatchers.eq("scheduled"), org.mockito.ArgumentMatchers.eq("published"),
                org.mockito.ArgumentMatchers.eq("publish"),
                org.mockito.ArgumentMatchers.<UUID>isNull(),
                org.mockito.ArgumentMatchers.contains("scheduled"));
    }

    @Test
    @DisplayName("before the deadline the clock does nothing at all")
    void aScheduledTransitionBeforeItsTimeDoesNothing() {
        WorkflowDefinition definition = template(TIMER);
        WorkflowInstance instance = instanceAt(definition, "scheduled");
        WorkflowTask waiting = new WorkflowTask(organizationId, instance.getId(), "scheduled",
                WorkflowDefinitionSpec.Assignee.role("ORG_ADMIN"), null);
        stubTimerLookup(instance, definition, waiting);

        int acted = maintenance.runDue(organizationId, Instant.now().plus(Duration.ofHours(1)));

        assertThat(acted).isZero();
        assertThat(instance.getCurrentState()).isEqualTo("scheduled");
        assertThat(waiting.getStatus()).isEqualTo(WorkflowTask.Status.OPEN);
        verify(history, never()).append(any(), any(), any(), any(), any(), any(), any());
        verify(events, never()).publish(any(PlatformEvent.class));
    }

    // ------------------------------------------------------------- SLA escalation

    @Test
    @DisplayName("a missed deadline escalates once, pulls in the destination and sticks the instance")
    void aMissedDeadlineEscalates() {
        WorkflowDefinition definition = template(escalating());
        WorkflowInstance instance = instanceAt(definition, "in_review");
        WorkflowTask adminTask = new WorkflowTask(organizationId, instance.getId(), "in_review",
                WorkflowDefinitionSpec.Assignee.role("ORG_ADMIN"), Instant.now().minusSeconds(3600));
        List<WorkflowTask> store = new ArrayList<>(List.of(adminTask));
        stubEscalationQueries(instance, definition, store);

        int acted = maintenance.runDue(organizationId, Instant.now());

        assertThat(acted).isEqualTo(1);
        assertThat(adminTask.getStatus())
                .as("the work is not confiscated: the assignee may still act")
                .isEqualTo(WorkflowTask.Status.OPEN);
        assertThat(adminTask.getEscalatedAt()).isNotNull();
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstance.Status.STUCK);

        ArgumentCaptor<WorkflowTask> saved = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(tasks, times(2)).save(saved.capture());
        WorkflowTask escalation = saved.getAllValues().get(1);
        assertThat(escalation.isEscalation()).isTrue();
        assertThat(escalation.getAssigneeType()).isEqualTo(WorkflowDefinitionSpec.AssigneeType.USER);
        assertThat(escalation.getAssigneeId()).isEqualTo(escalationHolder.toString());
        assertThat(escalation.getStatus()).isEqualTo(WorkflowTask.Status.OPEN);
        assertThat(escalation.getDueAt()).isNull();

        ArgumentCaptor<NotificationService.NotifyCommand> notified =
                ArgumentCaptor.forClass(NotificationService.NotifyCommand.class);
        verify(notifications).notify(notified.capture());
        assertThat(notified.getValue().userId()).isEqualTo(escalationHolder);
        assertThat(notified.getValue().templateKey()).isEqualTo("workflow.task.escalated");

        verify(history).append(org.mockito.ArgumentMatchers.eq(organizationId),
                org.mockito.ArgumentMatchers.eq(instance.getId()),
                org.mockito.ArgumentMatchers.eq("in_review"), org.mockito.ArgumentMatchers.eq("in_review"),
                org.mockito.ArgumentMatchers.eq("sla_escalate"),
                org.mockito.ArgumentMatchers.<UUID>isNull(), any());

        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(events).publish(published.capture());
        assertThat(published.getValue().eventType()).isEqualTo("workflow.task.escalated");
    }

    @Test
    @DisplayName("two overdue tasks at one state raise one escalation task, not two")
    void twoOverdueTasksRaiseOneEscalation() {
        WorkflowDefinition definition = template(escalating());
        WorkflowInstance instance = instanceAt(definition, "in_review");
        WorkflowTask adminTask = new WorkflowTask(organizationId, instance.getId(), "in_review",
                WorkflowDefinitionSpec.Assignee.role("ORG_ADMIN"), Instant.now().minusSeconds(7200));
        WorkflowTask editorTask = new WorkflowTask(organizationId, instance.getId(), "in_review",
                WorkflowDefinitionSpec.Assignee.role("EDITOR"), Instant.now().minusSeconds(3600));
        List<WorkflowTask> store = new ArrayList<>(List.of(adminTask, editorTask));
        stubEscalationQueries(instance, definition, store);

        int acted = maintenance.runDue(organizationId, Instant.now());

        assertThat(acted).isEqualTo(2);
        assertThat(adminTask.getEscalatedAt()).isNotNull();
        assertThat(editorTask.getEscalatedAt()).isNotNull();

        // Two marks plus exactly one escalation task.
        verify(tasks, times(3)).save(any(WorkflowTask.class));
        long escalationTasks = store.stream().filter(WorkflowTask::isEscalation).count();
        assertThat(escalationTasks)
                .as("one destination, one escalation task: the holder's queue is not doubled")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an overdue task with no escalation destination stays overdue and visible")
    void anOverdueTaskWithoutADestinationStaysOverdue() {
        WorkflowDefinition definition = template(NO_DESTINATION);
        WorkflowInstance instance = instanceAt(definition, "in_review");
        WorkflowTask adminTask = new WorkflowTask(organizationId, instance.getId(), "in_review",
                WorkflowDefinitionSpec.Assignee.role("ORG_ADMIN"), Instant.now().minusSeconds(3600));
        List<WorkflowTask> store = new ArrayList<>(List.of(adminTask));
        stubEscalationQueries(instance, definition, store);

        int acted = maintenance.runDue(organizationId, Instant.now());

        assertThat(acted).isZero();
        assertThat(adminTask.getEscalatedAt()).isNull();
        assertThat(adminTask.getStatus()).isEqualTo(WorkflowTask.Status.OPEN);
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstance.Status.RUNNING);
        verify(notifications, never()).notify(any(NotificationService.NotifyCommand.class));
    }

    @Test
    @DisplayName("the next sweep does not escalate the same task twice")
    void escalationHappensOncePerTask() {
        WorkflowDefinition definition = template(escalating());
        WorkflowInstance instance = instanceAt(definition, "in_review");
        WorkflowTask adminTask = new WorkflowTask(organizationId, instance.getId(), "in_review",
                WorkflowDefinitionSpec.Assignee.role("ORG_ADMIN"), Instant.now().minusSeconds(3600));
        List<WorkflowTask> store = new ArrayList<>(List.of(adminTask));
        stubEscalationQueries(instance, definition, store);

        assertThat(maintenance.runDue(organizationId, Instant.now())).isEqualTo(1);
        assertThat(maintenance.runDue(organizationId, Instant.now()))
                .as("the escalation marks the task, and the query excludes escalated tasks")
                .isZero();
        assertThat(store.stream().filter(WorkflowTask::isEscalation).count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private static WorkflowDefinition template(String json) {
        return WorkflowFixtures.template(WorkflowService.DEFAULT_DEFINITION_KEY, 1, json);
    }

    private String escalating() {
        return ESCALATING.formatted(escalationHolder, escalationHolder);
    }

    private WorkflowInstance instanceAt(WorkflowDefinition definition, String state) {
        return new WorkflowInstance(organizationId, definition, state, SUBJECT_TYPE, subjectId);
    }

    private void stubTimerLookup(WorkflowInstance instance, WorkflowDefinition definition,
                                 WorkflowTask openTask) {
        lenient().when(instances.findByOrganizationIdAndStatusIn(org.mockito.ArgumentMatchers.eq(organizationId),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(instance));
        lenient().when(definitions.findVisibleToTenant(definition.getId(), organizationId))
                .thenReturn(java.util.Optional.of(definition));
        lenient().when(tasks.findByOrganizationIdAndInstanceIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(openTask));
        lenient().when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(tasks.save(any(WorkflowTask.class))).thenAnswer(call -> call.getArgument(0));
    }

    /**
     * Task queries answered from a live list the saves mutate, so the sweep's own writes are
     * visible to the sweep's next read — the dedupe rule and the escalate-once rule are
     * exactly what a static stub would fake away.
     */
    private void stubEscalationQueries(WorkflowInstance instance, WorkflowDefinition definition,
                                       List<WorkflowTask> store) {
        lenient().when(instances.findByOrganizationIdAndStatusIn(org.mockito.ArgumentMatchers.eq(organizationId),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of());
        lenient().when(definitions.findVisibleToTenant(definition.getId(), organizationId))
                .thenReturn(java.util.Optional.of(definition));
        lenient().when(instances.findByIdAndOrganizationId(instance.getId(), organizationId))
                .thenReturn(java.util.Optional.of(instance));
        lenient().when(instances.save(any(WorkflowInstance.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(tasks.save(any(WorkflowTask.class))).thenAnswer(call -> {
            WorkflowTask saved = call.getArgument(0);
            if (store.stream().noneMatch(existing -> existing == saved)) {
                store.add(saved);
            }
            return saved;
        });
        lenient().when(tasks.findByOrganizationIdAndInstanceIdAndStatusIn(any(), any(), any()))
                .thenAnswer(call -> store.stream().filter(WorkflowTask::isOpen).toList());
        lenient().when(tasks.findByOrganizationIdAndStatusAndDueAtBeforeAndEscalatedAtIsNull(
                any(), any(), any()))
                .thenAnswer(call -> {
                    Instant now = call.getArgument(2);
                    return store.stream()
                            .filter(WorkflowTask::isOpen)
                            .filter(task -> task.getEscalatedAt() == null)
                            .filter(task -> task.getDueAt() != null && task.getDueAt().isBefore(now))
                            .toList();
                });
    }
}
