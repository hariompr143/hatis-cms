package com.hatis.platform.workflow.domain;

import com.hatis.platform.shared.error.PlatformExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation of the parsed definition.
 *
 * <p>A definition is data, so every one of these mistakes is a real customer-authored
 * document rather than a hypothetical: a state named in a transition but not declared, the
 * same action declared twice from one state, a typo in an assignee type. Each has to fail
 * when the definition is read, because the alternative is failing in the middle of somebody's
 * approval — or, worse, approving something the definition did not intend.
 */
@DisplayName("Workflow definition specification")
class WorkflowDefinitionSpecTest {

    private static final String EDITOR = "EDITOR";

    @Test
    @DisplayName("a well-formed definition exposes its transitions, terminal states and actions")
    void aWellFormedDefinitionIsReadable() {
        WorkflowDefinitionSpec spec = spec();

        assertThat(spec.initialState()).isEqualTo("draft");
        assertThat(spec.transition("draft", "submit")).isPresent();
        assertThat(spec.transition("draft", "publish")).isEmpty();
        assertThat(spec.isTerminal("published")).isTrue();
        assertThat(spec.isTerminal("in_review")).isFalse();
        assertThat(spec.transitionsFrom("draft")).extracting(WorkflowDefinitionSpec.Transition::action)
                .containsExactly("submit");
    }

    @Test
    @DisplayName("the initial state must be one of the declared states")
    void theInitialStateMustBeDeclared() {
        assertThatThrownBy(() -> new WorkflowDefinitionSpec("publishd", List.of("draft", "published"),
                List.of(transition("draft", "published", "publish"))))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("initialState 'publishd' is not one of the declared states");
    }

    @Test
    @DisplayName("a transition may not name a state the definition does not declare")
    void transitionsMustStayInsideTheDeclaredStates() {
        assertThatThrownBy(() -> new WorkflowDefinitionSpec("draft", List.of("draft"),
                List.of(transition("draft", "in_review", "submit"))))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("ends at unknown state 'in_review'");

        assertThatThrownBy(() -> new WorkflowDefinitionSpec("draft", List.of("draft"),
                List.of(transition("review", "draft", "rework"))))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("starts at unknown state 'review'");
    }

    @Test
    @DisplayName("the same action twice from one state is refused, because the result would depend on order")
    void oneActionPerStateIsEnforced() {
        assertThatThrownBy(() -> new WorkflowDefinitionSpec("draft", List.of("draft", "in_review"),
                List.of(transition("draft", "in_review", "submit"),
                        transition("draft", "in_review", "submit"))))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("declared twice from state 'draft'");
    }

    @Test
    @DisplayName("the same action may leave different states")
    void anActionMayRepeatAcrossStates() {
        WorkflowDefinitionSpec spec = new WorkflowDefinitionSpec("draft",
                List.of("draft", "in_review", "rejected"),
                List.of(transition("draft", "in_review", "submit"),
                        transition("rejected", "in_review", "submit")));

        assertThat(spec.transitionsFrom("draft")).hasSize(1);
        assertThat(spec.transitionsFrom("rejected")).hasSize(1);
    }

    @Test
    @DisplayName("a definition with no transitions at all is refused")
    void anEmptyTransitionListIsRefused() {
        assertThatThrownBy(() -> new WorkflowDefinitionSpec("draft", List.of("draft"), List.of()))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("at least one transition");
    }

    @Test
    @DisplayName("a state name longer than the column that stores it is refused")
    void stateNamesAreBoundedByTheColumn() {
        String tooLong = "s".repeat(WorkflowDefinitionSpec.MAX_STATE_LENGTH + 1);

        assertThatThrownBy(() -> new WorkflowDefinitionSpec(tooLong, List.of(tooLong),
                List.of(transition(tooLong, tooLong, "loop"))))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("is longer than 80 characters");
    }

    @Test
    @DisplayName("repeating a state name is refused rather than silently collapsed")
    void statesMayNotRepeat() {
        assertThatThrownBy(() -> new WorkflowDefinitionSpec("draft", List.of("draft", "draft"),
                List.of(transition("draft", "draft", "loop"))))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("must not repeat a state name");
    }

    @Test
    @DisplayName("an ANY assignee permits any principal and may not name an id")
    void anyonePermitsEverybody() {
        WorkflowDefinitionSpec.Assignee anyone = WorkflowDefinitionSpec.Assignee.anyone();

        assertThat(anyone.permits(UUID.randomUUID(), List.of())).isTrue();
        assertThat(anyone.permits(null, List.of("EDITOR"))).isTrue();
        assertThatThrownBy(() -> new WorkflowDefinitionSpec.Assignee(
                WorkflowDefinitionSpec.AssigneeType.ANY, EDITOR))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("must not name an id");
    }

    @Test
    @DisplayName("a ROLE assignee matches a held role and nothing else")
    void aRoleAssigneeMatchesHeldRoles() {
        WorkflowDefinitionSpec.Assignee assignee = WorkflowDefinitionSpec.Assignee.role(EDITOR);

        assertThat(assignee.permits(UUID.randomUUID(), List.of(EDITOR))).isTrue();
        assertThat(assignee.permits(UUID.randomUUID(), List.of("ORG_ADMIN"))).isFalse();
        assertThat(assignee.permits(UUID.randomUUID(), List.of())).isFalse();
        assertThat(assignee.permits(UUID.randomUUID(), null)).isFalse();
    }

    @Test
    @DisplayName("a USER assignee matches exactly that principal")
    void aUserAssigneeMatchesOnePrincipal() {
        UUID named = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        WorkflowDefinitionSpec.Assignee assignee = WorkflowDefinitionSpec.Assignee.user(named);

        assertThat(assignee.permits(named, List.of())).isTrue();
        assertThat(assignee.permits(other, List.of("EDITOR"))).isFalse();
        assertThat(assignee.permits(null, List.of("EDITOR"))).isFalse();
    }

    @Test
    @DisplayName("a USER assignee whose id is not a UUID is refused when the definition is read")
    void aUserAssigneeMustBeAUuid() {
        assertThatThrownBy(() -> new WorkflowDefinitionSpec.Assignee(
                WorkflowDefinitionSpec.AssigneeType.USER, "editor@example.com"))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("not a UUID");
    }

    @Test
    @DisplayName("a ROLE or USER assignee without an id is refused")
    void assigneesOtherThanAnyoneNeedAnId() {
        assertThatThrownBy(() -> new WorkflowDefinitionSpec.Assignee(
                WorkflowDefinitionSpec.AssigneeType.ROLE, " "))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("missing 'id'");
    }

    @Test
    @DisplayName("a negative SLA is refused rather than becoming a due date in the past")
    void slaHoursMayNotBeNegative() {
        assertThatThrownBy(() -> new WorkflowDefinitionSpec.Transition(
                "draft", "in_review", "submit", WorkflowDefinitionSpec.Assignee.role(EDITOR), -1, null))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("slaHours may not be negative");
    }

    @Test
    @DisplayName("assineesFor returns one entry per distinct assignee leaving a state")
    void assigneesForDeduplicates() {
        WorkflowDefinitionSpec spec = new WorkflowDefinitionSpec("draft",
                List.of("draft", "approved", "rejected"),
                List.of(transition("draft", "approved", "approve"),
                        transition("draft", "rejected", "reject")));

        assertThat(spec.assigneesFor("draft")).containsExactly(WorkflowDefinitionSpec.Assignee.role(EDITOR));
    }

    @Test
    @DisplayName("the spec is immutable once parsed")
    void aMutableListCannotChangeAParsedSpec() {
        List<WorkflowDefinitionSpec.Transition> transitions =
                new ArrayList<>(List.of(transition("draft", "published", "publish")));
        WorkflowDefinitionSpec spec = new WorkflowDefinitionSpec("draft", List.of("draft", "published"),
                transitions);

        transitions.clear();

        assertThat(spec.transitions()).hasSize(1);
        assertThatThrownBy(() -> spec.transitions().add(transition("a", "b", "c")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static WorkflowDefinitionSpec spec() {
        return new WorkflowDefinitionSpec("draft", List.of("draft", "in_review", "published"),
                List.of(transition("draft", "in_review", "submit"),
                        transition("in_review", "published", "publish")));
    }

    private static WorkflowDefinitionSpec.Transition transition(String from, String to, String action) {
        return new WorkflowDefinitionSpec.Transition(from, to, action,
                WorkflowDefinitionSpec.Assignee.role(EDITOR), null, null);
    }
}
