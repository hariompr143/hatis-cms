package com.hatis.platform.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.workflow.domain.WorkflowDefinitionSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a stored definition.
 *
 * <p>The first test parses the template the migration actually ships, read out of the
 * migration file rather than copied into this class. A copy would pass while the real
 * template was unloadable, which is the failure that matters: the engine refuses a document
 * it cannot fully understand, so a template the parser rejects leaves every tenant without a
 * workflow and every submit answering 404.
 */
@DisplayName("Workflow definition parser")
class WorkflowDefinitionParserTest {

    private static final Pattern SEEDED_TEMPLATE =
            Pattern.compile("(?s)(\\{\\s*\"initialState\".*?)\\}'\\s*::jsonb");

    private final WorkflowDefinitionParser parser =
            new WorkflowDefinitionParser(new ObjectMapper());

    @Test
    @DisplayName("the template seeded by V1_008 parses, with the transitions it documents")
    void theSeededTemplateParses() throws IOException {
        WorkflowDefinitionSpec spec = parser.parse(seededTemplate());

        assertThat(spec.initialState()).isEqualTo("draft");
        assertThat(spec.states()).containsExactlyInAnyOrder(
                "draft", "in_review", "approved", "published", "rejected");
        assertThat(spec.transitions()).hasSize(5);
        WorkflowDefinitionSpec.Transition submit = transition(spec, "draft", "submit");
        assertThat(submit.assignee().type()).isEqualTo(WorkflowDefinitionSpec.AssigneeType.ROLE);
        assertThat(submit.assignee().id()).isEqualTo("EDITOR");

        WorkflowDefinitionSpec.Transition approve = transition(spec, "in_review", "approve");
        assertThat(approve.assignee().type()).isEqualTo(WorkflowDefinitionSpec.AssigneeType.ROLE);
        assertThat(approve.assignee().id()).isEqualTo("ORG_ADMIN");

        assertThat(spec.transition("rejected", "rework")).isPresent();

        // Rejection is not the end of the flow — the seeded definition lets an editor rework
        // it — while publication is. Pinned because "rejected" reads like a terminal state
        // and is not one, and an engine that treated it as terminal would finish an item that
        // the definition says can be revised.
        assertThat(spec.isTerminal("published")).isTrue();
        assertThat(spec.isTerminal("rejected")).isFalse();
    }

    @Test
    @DisplayName("an assignee of each type is read as written")
    void eachAssigneeTypeIsRead() {
        WorkflowDefinitionSpec spec = parser.parse("""
                {
                  "initialState": "draft",
                  "states": ["draft", "approved"],
                  "transitions": [
                    {"from": "draft", "to": "approved", "action": "approve",
                     "assignee": {"type": "USER", "id": "6c1d3f2e-2b6a-4a2f-9f2b-1f0a5c9d7e11"}}
                  ]
                }
                """);

        WorkflowDefinitionSpec.Transition approve = transition(spec, "draft", "approve");
        assertThat(approve.assignee().type()).isEqualTo(WorkflowDefinitionSpec.AssigneeType.USER);
        assertThat(approve.assignee().id()).isEqualTo("6c1d3f2e-2b6a-4a2f-9f2b-1f0a5c9d7e11");
    }

    @Test
    @DisplayName("a transition with no assignee is open to anyone holding the permission")
    void aMissingAssigneeMeansAnyone() {
        WorkflowDefinitionSpec spec = parser.parse("""
                {"initialState": "a", "states": ["a", "b"],
                 "transitions": [{"from": "a", "to": "b", "action": "go"}]}
                """);

        assertThat(transition(spec, "a", "go").assignee().type())
                .isEqualTo(WorkflowDefinitionSpec.AssigneeType.ANY);
    }

    @Test
    @DisplayName("the legacy requiredRole spelling is accepted as a role assignee")
    void requiredRoleIsAccepted() {
        WorkflowDefinitionSpec spec = parser.parse("""
                {"initialState": "a", "states": ["a", "b"],
                 "transitions": [{"from": "a", "to": "b", "action": "go", "requiredRole": "EDITOR"}]}
                """);

        WorkflowDefinitionSpec.Transition go = transition(spec, "a", "go");
        assertThat(go.assignee().type()).isEqualTo(WorkflowDefinitionSpec.AssigneeType.ROLE);
        assertThat(go.assignee().id()).isEqualTo("EDITOR");
    }

    @Test
    @DisplayName("slaHours and an explicit outcome are read")
    void slaHoursAndOutcomeAreRead() {
        WorkflowDefinitionSpec spec = parser.parse("""
                {"initialState": "a", "states": ["a", "b"],
                 "transitions": [{"from": "a", "to": "b", "action": "go",
                                  "slaHours": 72, "outcome": "REJECTED"}]}
                """);

        WorkflowDefinitionSpec.Transition go = transition(spec, "a", "go");
        assertThat(go.slaHours()).isEqualTo(72);
        assertThat(go.outcome()).isEqualTo(WorkflowDefinitionSpec.Outcome.REJECTED);
    }

    @Test
    @DisplayName("a guard is refused, because ignoring it would run rules the customer configured")
    void aGuardIsRefused() {
        assertThatThrownBy(() -> parser.parse("""
                {"initialState": "a", "states": ["a", "b"], "guard": {"field": "approved"},
                 "transitions": [{"from": "a", "to": "b", "action": "go"}]}
                """))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("'guard' is not supported");
    }

    @Test
    @DisplayName("a misspelt transition key is refused rather than ignored")
    void anUnknownTransitionKeyIsRefused() {
        assertThatThrownBy(() -> parser.parse("""
                {"initialState": "a", "states": ["a", "b"],
                 "transitions": [{"from": "a", "to": "b", "action": "go",
                                  "assigne": {"type": "ROLE", "id": "EDITOR"}}]}
                """))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("unknown key 'assigne'");
    }

    @Test
    @DisplayName("an unknown top-level key is refused")
    void anUnknownTopLevelKeyIsRefused() {
        assertThatThrownBy(() -> parser.parse("""
                {"initialState": "a", "states": ["a", "b"], "timeout": 30,
                 "transitions": [{"from": "a", "to": "b", "action": "go"}]}
                """))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("unknown key 'timeout'");
    }

    @Test
    @DisplayName("an unknown assignee type is refused, naming the types that exist")
    void anUnknownAssigneeTypeIsRefused() {
        assertThatThrownBy(() -> parser.parse("""
                {"initialState": "a", "states": ["a", "b"],
                 "transitions": [{"from": "a", "to": "b", "action": "go",
                                  "assignee": {"type": "EDITOR", "id": "x"}}]}
                """))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("is not one of ROLE, USER, ANY");
    }

    @Test
    @DisplayName("assignee and requiredRole together are refused, because they could disagree")
    void bothAssigneeSpellingsAreRefused() {
        assertThatThrownBy(() -> parser.parse("""
                {"initialState": "a", "states": ["a", "b"],
                 "transitions": [{"from": "a", "to": "b", "action": "go",
                                  "requiredRole": "EDITOR",
                                  "assignee": {"type": "ROLE", "id": "ORG_ADMIN"}}]}
                """))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("declares both");
    }

    @Test
    @DisplayName("malformed JSON is refused without quoting the stored document back")
    void malformedJsonIsRefusedWithoutEchoingIt() {
        assertThatThrownBy(() -> parser.parse("{\"initialState\": \"draft\", "))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("not valid JSON")
                .hasMessageNotContaining("draft");
    }

    @Test
    @DisplayName("an empty document is refused")
    void anEmptyDocumentIsRefused() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("definition is empty");
    }

    @Test
    @DisplayName("a definition whose states are not an array is refused")
    void statesMustBeAnArray() {
        assertThatThrownBy(() -> parser.parse("""
                {"initialState": "a", "states": "a,b",
                 "transitions": [{"from": "a", "to": "b", "action": "go"}]}
                """))
                .isInstanceOf(PlatformExceptions.Validation.class)
                .hasMessageContaining("states must be an array");
    }

    private static WorkflowDefinitionSpec.Transition transition(WorkflowDefinitionSpec spec,
                                                               String from, String action) {
        return spec.transition(from, action)
                .orElseThrow(() -> new AssertionError("no '" + action + "' transition from " + from));
    }

    /**
     * The JSON document from the insert statement in {@code V1_008}, read from the file.
     *
     * <p>The same two candidate paths the integration tests use, for the same reason: the
     * migration directory resolves from the module directory when Maven runs, and from the
     * repository root when a test is run from an IDE. Neither is guessed at silently —
     * this fails the test if the file cannot be found, because a parser test that quietly
     * skipped would be worse than no test.
     */
    private static String seededTemplate() throws IOException {
        for (String candidate : new String[]{
                "src/main/resources/db/migration/V1_008__workflow.sql",
                "../hatis-api/src/main/resources/db/migration/V1_008__workflow.sql",
                "backend/hatis-api/src/main/resources/db/migration/V1_008__workflow.sql"}) {
            Path path = Path.of(candidate);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            Matcher matcher = SEEDED_TEMPLATE.matcher(Files.readString(path));
            if (matcher.find()) {
                return matcher.group(1) + "}";
            }
            throw new IllegalStateException(
                    "V1_008 no longer seeds a definition this pattern can find, so the test "
                            + "that proves the shipped template parses has stopped proving it");
        }
        throw new IllegalStateException(
                "V1_008__workflow.sql was not found from " + Path.of("").toAbsolutePath());
    }
}
