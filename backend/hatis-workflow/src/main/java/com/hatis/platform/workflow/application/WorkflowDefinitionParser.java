package com.hatis.platform.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.workflow.domain.WorkflowDefinitionSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a workflow definition out of the {@code jsonb} column it is stored in.
 *
 * <p>This is the boundary where stored data becomes a validated
 * {@link WorkflowDefinitionSpec}, and it lives in the application layer because it is the
 * only layer allowed to know about JSON. The domain stays free of Jackson, which is what
 * would let the engine run in a process that does not have it.
 *
 * <h2>Unsupported syntax is refused, not ignored</h2>
 *
 * Two fields are read but refused rather than skipped, because silently dropping either
 * would run a workflow that is not the one the customer configured:
 *
 * <ul>
 *   <li>{@code guard} — the engine evaluates no conditions in Phase 1.</li>
 *   <li>any unknown key inside a transition, which is almost always a typo of a key the
 *       engine reads ({@code assigne}, {@code requiredRoles}), and a typo that is ignored
 *       changes who may approve.</li>
 * </ul>
 *
 * <p>{@code requiredRole} <em>is</em> accepted, as an alias for
 * {@code assignee: {type: ROLE, id: …}}, because the schema's own comment documents the
 * definition as {@code { from, to, action, guard, requiredRole }} — two spellings exist in
 * the platform's documentation and refusing one of them would make a documented definition
 * unloadable.
 */
@Component
public class WorkflowDefinitionParser {

    private static final List<String> TRANSITION_KEYS =
            List.of("from", "to", "action", "assignee", "requiredRole", "slaHours", "outcome");

    private final ObjectMapper mapper;

    public WorkflowDefinitionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public WorkflowDefinitionSpec parse(String document) {
        JsonNode root = readTree(document);
        if (root == null || !root.isObject()) {
            throw invalid("the definition must be a JSON object");
        }
        root.fieldNames().forEachRemaining(name -> {
            if (!List.of("initialState", "states", "transitions", "guard").contains(name)) {
                throw invalid("unknown key '" + name + "' at the top level");
            }
        });
        if (root.hasNonNull("guard")) {
            throw invalid("'guard' is not supported by this engine, so a definition that "
                    + "declares one is refused rather than evaluated without it");
        }

        String initialState = text(root, "initialState")
                .orElseThrow(() -> invalid("initialState is required"));

        List<String> states = new ArrayList<>();
        JsonNode stateNodes = root.get("states");
        if (stateNodes == null || !stateNodes.isArray()) {
            throw invalid("states must be an array");
        }
        stateNodes.forEach(node -> {
            if (!node.isTextual()) {
                throw invalid("states must contain strings");
            }
            states.add(node.asText());
        });

        List<WorkflowDefinitionSpec.Transition> transitions = new ArrayList<>();
        JsonNode transitionNodes = root.get("transitions");
        if (transitionNodes == null || !transitionNodes.isArray()) {
            throw invalid("transitions must be an array");
        }
        transitionNodes.forEach(node -> transitions.add(parseTransition(node)));

        return new WorkflowDefinitionSpec(initialState, states, transitions);
    }

    private WorkflowDefinitionSpec.Transition parseTransition(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("each transition must be a JSON object");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!TRANSITION_KEYS.contains(name)) {
                throw invalid("unknown key '" + name + "' on a transition; accepted keys are "
                        + TRANSITION_KEYS);
            }
        });
        if (node.hasNonNull("guard")) {
            throw invalid("'guard' is not supported by this engine");
        }

        String from = text(node, "from").orElseThrow(() -> invalid("a transition is missing 'from'"));
        String to = text(node, "to").orElseThrow(() -> invalid("a transition is missing 'to'"));
        String action = text(node, "action").orElseThrow(() -> invalid("a transition is missing 'action'"));

        WorkflowDefinitionSpec.Assignee assignee = parseAssignee(node);
        Integer slaHours = node.hasNonNull("slaHours") ? node.get("slaHours").asInt() : null;
        WorkflowDefinitionSpec.Outcome outcome = text(node, "outcome")
                .map(WorkflowDefinitionParser::parseOutcome)
                .orElse(null);

        return new WorkflowDefinitionSpec.Transition(from, to, action, assignee, slaHours, outcome);
    }

    private WorkflowDefinitionSpec.Assignee parseAssignee(JsonNode node) {
        JsonNode assignee = node.get("assignee");
        Optional<String> legacyRole = text(node, "requiredRole");

        if (assignee != null && assignee.isObject() && legacyRole.isPresent()) {
            throw invalid("a transition declares both 'assignee' and 'requiredRole'");
        }
        if (legacyRole.isPresent()) {
            return WorkflowDefinitionSpec.Assignee.role(legacyRole.get());
        }
        if (assignee == null || assignee.isNull()) {
            return WorkflowDefinitionSpec.Assignee.anyone();
        }
        if (!assignee.isObject()) {
            throw invalid("'assignee' must be an object with 'type' and, unless the type is ANY, 'id'");
        }
        assignee.fieldNames().forEachRemaining(name -> {
            if (!List.of("type", "id").contains(name)) {
                throw invalid("unknown key '" + name + "' on an assignee");
            }
        });
        String type = text(assignee, "type").orElseThrow(() -> invalid("an assignee is missing 'type'"));
        String id = text(assignee, "id").orElse(null);
        return new WorkflowDefinitionSpec.Assignee(parseAssigneeType(type), id);
    }

    private static WorkflowDefinitionSpec.AssigneeType parseAssigneeType(String value) {
        try {
            return WorkflowDefinitionSpec.AssigneeType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("assignee type '" + value + "' is not one of ROLE, USER, ANY");
        }
    }

    private static WorkflowDefinitionSpec.Outcome parseOutcome(String value) {
        try {
            return WorkflowDefinitionSpec.Outcome.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("outcome '" + value + "' is not one of COMPLETED, REJECTED, CANCELLED");
        }
    }

    private JsonNode readTree(String document) {
        if (document == null || document.isBlank()) {
            throw invalid("the definition is empty");
        }
        try {
            return mapper.readTree(document);
        } catch (Exception e) {
            // The message from Jackson can quote offsets and the offending text; the stored
            // document may hold customer-authored state names, so only the failure is reported.
            throw invalid("the definition is not valid JSON");
        }
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText().trim());
    }

    private static PlatformExceptions.Validation invalid(String message) {
        return WorkflowDefinitionSpec.invalid(message);
    }
}
