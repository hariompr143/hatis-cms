package com.hatis.platform.cms.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.cms.adapter.persistence.ContentRepositories;
import com.hatis.platform.cms.domain.ContentItem;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.tenant.TenantContext;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.workflow.application.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

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
 * The item and the workflow moving together — in that order, and by the right permission.
 *
 * <p>The engine owns the states; the item owns the version pointer. What this test pins down is
 * the seam between them, because a seam like this is where an approval can be recorded twice or
 * an item can be marked approved while the workflow is still waiting for somebody. Submitting
 * starts the workflow; approving and rejecting are decided by the workflow first and the item
 * only if the workflow accepted the move, so a forbidden decision cannot leave an item approved
 * with no reviewer.
 *
 * <p>The permission codes are checked here as well, not as a spelling exercise: the module used
 * to ask for {@code content:read} and {@code content:publish}, which the catalogue does not
 * define, and because the authorization service matches codes against grants, every content
 * endpoint denied everybody — including the owner.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Content review")
class ContentReviewTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID principalId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID instanceId = UUID.randomUUID();

    @Mock
    private ContentRepositories.ContentTypeRepository types;
    @Mock
    private ContentRepositories.ContentItemRepository items;
    @Mock
    private ContentRepositories.ContentVersionRepository versions;
    @Mock
    private AuthorizationService authorization;
    @Mock
    private QuotaService quotas;
    @Mock
    private EventPublisher events;
    @Mock
    private WorkflowService workflows;

    private ContentService service;

    @BeforeEach
    void setUp() {
        service = new ContentService(types, items, versions, authorization, quotas, events,
                new ObjectMapper(), workflows);
        TenantContextHolder.set(TenantContext.of(organizationId, principalId,
                TenantContext.PrincipalType.USER));
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("submitting starts a review, opens the reviewer's task, and marks the item in review")
    void submittingStartsAReview() {
        ContentItem item = item(ContentItem.Status.DRAFT, null);
        givenItem(item);
        when(workflows.start(any())).thenReturn(instanceDetail("in_review"));
        when(workflows.transition(any())).thenReturn(instanceDetail("in_review"));

        ContentService.ContentDetail detail = service.submitForReview(itemId);

        ArgumentCaptor<WorkflowService.StartCommand> started =
                ArgumentCaptor.forClass(WorkflowService.StartCommand.class);
        verify(workflows).start(started.capture());
        assertThat(started.getValue().projectId()).isEqualTo(projectId);
        assertThat(started.getValue().subjectType()).isEqualTo(ContentService.REVIEW_SUBJECT_TYPE);
        assertThat(started.getValue().subjectId()).isEqualTo(itemId);
        assertThat(started.getValue().definitionKey()).isEqualTo(WorkflowService.DEFAULT_DEFINITION_KEY);

        ArgumentCaptor<WorkflowService.TransitionCommand> submitted =
                ArgumentCaptor.forClass(WorkflowService.TransitionCommand.class);
        verify(workflows).transition(submitted.capture());
        assertThat(submitted.getValue().instanceId()).isEqualTo(instanceId);
        assertThat(submitted.getValue().action()).isEqualTo("submit");

        assertThat(item.getStatus()).isEqualTo(ContentItem.Status.IN_REVIEW);
        assertThat(item.getWorkflowInstanceId()).isEqualTo(instanceId);
        assertThat(detail.status()).isEqualTo("IN_REVIEW");
        assertThat(detail.workflowInstanceId()).isEqualTo(instanceId);

        verify(authorization).require(ContentService.SUBMIT_PERMISSION, ScopeType.PROJECT, projectId);
        verify(events).publish(any(PlatformEvent.class));
    }

    @Test
    @DisplayName("submitting something already in review starts no second workflow")
    void aSecondSubmissionDoesNotForkTheReview() {
        ContentItem item = item(ContentItem.Status.IN_REVIEW, instanceId);
        givenItem(item);
        when(workflows.instance(instanceId)).thenReturn(instanceDetail("in_review"));
        when(workflows.transition(any())).thenThrow(new PlatformExceptions.StateConflict(
                "No transition 'submit' leaves state 'in_review'"));

        assertThatThrownBy(() -> service.submitForReview(itemId))
                .as("the definition has no submit out of in_review, and the engine is what says so")
                .isInstanceOf(PlatformExceptions.StateConflict.class);

        verify(workflows, never()).start(any());
        assertThat(item.getStatus()).isEqualTo(ContentItem.Status.IN_REVIEW);
        verify(items, never()).save(item);
    }

    @Test
    @DisplayName("a rejected item is reworked before it can be reviewed again")
    void aRejectedItemIsReworkedFirst() {
        ContentItem item = item(ContentItem.Status.DRAFT, instanceId);
        givenItem(item);
        when(workflows.instance(instanceId)).thenReturn(instanceDetail("rejected"));
        when(workflows.transition(any())).thenReturn(instanceDetail("draft"));

        service.submitForReview(itemId);

        ArgumentCaptor<WorkflowService.TransitionCommand> commands =
                ArgumentCaptor.forClass(WorkflowService.TransitionCommand.class);
        verify(workflows, times(2)).transition(commands.capture());
        // The engine refuses 'submit' from 'rejected' — the seeded definition expects the author
        // to rework it first — so rework has to be attempted first, and only then submit.
        assertThat(commands.getAllValues())
                .extracting(WorkflowService.TransitionCommand::action)
                .containsExactly("rework", "submit");
        verify(workflows, never()).start(any());
        assertThat(item.getStatus()).isEqualTo(ContentItem.Status.IN_REVIEW);
    }

    @Test
    @DisplayName("approving moves the workflow and then the item")
    void approvingMovesBoth() {
        ContentItem item = item(ContentItem.Status.IN_REVIEW, instanceId);
        givenItem(item);
        when(workflows.transition(any())).thenReturn(instanceDetail("approved"));

        ContentService.ContentDetail detail = service.approve(itemId, "Looks good");

        ArgumentCaptor<WorkflowService.TransitionCommand> command =
                ArgumentCaptor.forClass(WorkflowService.TransitionCommand.class);
        verify(workflows).transition(command.capture());
        assertThat(command.getValue().instanceId()).isEqualTo(instanceId);
        assertThat(command.getValue().action()).isEqualTo("approve");
        assertThat(command.getValue().comment()).isEqualTo("Looks good");

        assertThat(item.getStatus()).isEqualTo(ContentItem.Status.APPROVED);
        assertThat(detail.status()).isEqualTo("APPROVED");
        verify(authorization).require(ContentService.APPROVE_PERMISSION, ScopeType.PROJECT, projectId);
    }

    @Test
    @DisplayName("a rejected item returns to draft and keeps the review it came from")
    void rejectingReturnsTheItemToDraft() {
        ContentItem item = item(ContentItem.Status.IN_REVIEW, instanceId);
        givenItem(item);
        when(workflows.transition(any())).thenReturn(instanceDetail("rejected"));

        ContentService.ContentDetail detail = service.reject(itemId, "The headline is too long");

        assertThat(item.getStatus()).isEqualTo(ContentItem.Status.DRAFT);
        assertThat(item.getWorkflowInstanceId())
                .as("the instance stays: it is what the author reworks")
                .isEqualTo(instanceId);
        assertThat(detail.status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("a decision the engine refuses leaves the item untouched")
    void aRefusedDecisionLeavesTheItemAlone() {
        ContentItem item = item(ContentItem.Status.IN_REVIEW, instanceId);
        givenItem(item);
        when(workflows.transition(any()))
                .thenThrow(new PlatformExceptions.Forbidden("'approve' is assigned to ORG_ADMIN"));

        assertThatThrownBy(() -> service.approve(itemId, null))
                .isInstanceOf(PlatformExceptions.Forbidden.class);

        assertThat(item.getStatus())
                .as("the workflow did not move, so the item must not claim it was approved")
                .isEqualTo(ContentItem.Status.IN_REVIEW);
        verify(items, never()).save(item);
    }

    @Test
    @DisplayName("deciding something that is not in review is refused before the workflow is touched")
    void decidingWithoutAReviewIsRefused() {
        givenItem(item(ContentItem.Status.DRAFT, null));

        assertThatThrownBy(() -> service.approve(itemId, null))
                .isInstanceOf(PlatformExceptions.StateConflict.class)
                .hasMessageContaining("no review");

        assertThatThrownBy(() -> service.reject(itemId, null))
                .isInstanceOf(PlatformExceptions.StateConflict.class)
                .hasMessageContaining("no review");

        verify(workflows, never()).transition(any());
    }

    @Test
    @DisplayName("the codes it checks are the ones the catalogue defines")
    void thePermissionCodesAreTheCatalogues() {
        assertThat(ContentService.READ_PERMISSION).isEqualTo("cms:content:read");
        assertThat(ContentService.WRITE_PERMISSION).isEqualTo("cms:content:write");
        assertThat(ContentService.SUBMIT_PERMISSION).isEqualTo("cms:content:submit");
        assertThat(ContentService.APPROVE_PERMISSION).isEqualTo("cms:content:approve");
        assertThat(ContentService.PUBLISH_PERMISSION).isEqualTo("cms:content:publish");
        assertThat(ContentService.TYPE_WRITE_PERMISSION).isEqualTo("cms:type:write");
    }

    // ---------------------------------------------------------------- helpers

    private void givenItem(ContentItem item) {
        when(items.findByIdAndOrganizationId(itemId, organizationId)).thenReturn(Optional.of(item));
        when(items.save(any(ContentItem.class))).thenAnswer(call -> call.getArgument(0));
        when(versions.findByIdAndOrganizationId(any(), eq(organizationId))).thenReturn(Optional.empty());
    }

    private ContentItem item(ContentItem.Status status, UUID workflowInstanceId) {
        ContentItem item = new ContentItem(organizationId, projectId, UUID.randomUUID(), "launch-notes",
                "en", principalId);
        ReflectionTestUtils.setField(item, "status", status);
        ReflectionTestUtils.setField(item, "workflowInstanceId", workflowInstanceId);
        ReflectionTestUtils.setField(item, "currentVersionId", UUID.randomUUID());
        return item;
    }

    private WorkflowService.InstanceDetail instanceDetail(String currentState) {
        return new WorkflowService.InstanceDetail(
                new WorkflowService.InstanceSummary(instanceId, ContentService.REVIEW_SUBJECT_TYPE, itemId,
                        currentState, "RUNNING", 1, null, null, null),
                WorkflowService.DEFAULT_DEFINITION_KEY, 1, null, List.of(), List.of(), List.of());
    }
}
