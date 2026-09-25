package com.hatis.platform.workflow.domain;

import com.hatis.platform.shared.error.PlatformExceptions;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A parsed workflow definition: states, transitions and who may act on each one.
 *
 * <p>The engine knows nothing about content, deployments or HR — it knows states and
 * transitions. That is what lets the same engine serve the CMS approval flow today
 * and another domain later without a rewrite, and it is why this type has no
 * reference to any other context.
 *
 * <h2>Validation happens at construction, not at use</h2>
 *
 * A definition is data (a {@code jsonb} column), so a malformed one is not a compile
 * error and not a database error either — PostgreSQL will happily store
 * {@code "initialState": "publishd"}. The only place it can be caught is the moment it
 * is read, which is here: every rule that could otherwise surface as a
 * {@code NullPointerException} in the middle of a customer's approval is checked in the
 * constructor, and the failure names the definition's problem rather than the line of
 * code that tripped over it.
 *
 * <h2>Why {@code guard} is refused rather than ignored</h2>
 *
 * The schema's comment for this column mentions a {@code guard}. The Phase 1 engine does
 * not evaluate guards, so a definition carrying one is <em>rejected</em>: silently
 * ignoring a guard would approve content that a customer configured to require a
 * condition first, and a workflow that runs the wrong rules is worse than one that
 * refuses to start.
 */
public record WorkflowDefinitionSpec(
        String initialState,
        List<String> states,
        List<Transition> transitions) {

    /** Matches {@code wf_tasks.assignee_type} and {@code wf_history.to_state} widths. */
    public static final int MAX_STATE_LENGTH = 80;
    public static final int MAX_ACTION_LENGTH = 120;
    public static final int MAX_ASSIGNEE_LENGTH = 120;

    public WorkflowDefinitionSpec {
        if (initialState == null || initialState.isBlank()) {
            throw invalid("initialState is required");
        }
        if (states == null || states.isEmpty()) {
            throw invalid("states must list at least one state");
        }
        if (transitions == null || transitions.isEmpty()) {
            throw invalid("transitions must list at least one transition");
        }

        Set<String> declared = new LinkedHashSet<>(states);
        if (declared.size() != states.size()) {
            throw invalid("states must not repeat a state name");
        }
        for (String state : declared) {
            if (state == null || state.isBlank()) {
                throw invalid("states must not contain a blank state name");
            }
            if (state.length() > MAX_STATE_LENGTH) {
                throw invalid("state '" + state + "' is longer than " + MAX_STATE_LENGTH
                        + " characters, which is the width of the column it is stored in");
            }
        }
        if (!declared.contains(initialState)) {
            throw invalid("initialState '" + initialState + "' is not one of the declared states");
        }

        Set<String> actions = new LinkedHashSet<>();
        for (Transition transition : transitions) {
            if (!declared.contains(transition.from())) {
                throw invalid("transition '" + transition.action() + "' starts at unknown state '"
                        + transition.from() + "'");
            }
            if (!declared.contains(transition.to())) {
                throw invalid("transition '" + transition.action() + "' ends at unknown state '"
                        + transition.to() + "'");
            }
            // Two transitions with the same action leaving the same state make the
            // engine's answer to "what does 'approve' mean here" depend on list order.
            if (!actions.add(transition.from() + '\u0000' + transition.action())) {
                throw invalid("action '" + transition.action() + "' is declared twice from state '"
                        + transition.from() + "', so the result would depend on declaration order");
            }
        }

        states = List.copyOf(declared);
        transitions = List.copyOf(transitions);
    }

    /** Every transition leaving {@code state}, in declaration order. */
    public List<Transition> transitionsFrom(String state) {
        return transitions.stream().filter(t -> t.from().equals(state)).toList();
    }

    /**
     * True when nothing leaves this state.
     *
     * <p>Terminal is a property of the definition, not of the instance: it is how the
     * engine knows an approval has finished rather than stalled.
     */
    public boolean isTerminal(String state) {
        return transitionsFrom(state).isEmpty();
    }

    /** The transition a caller means by {@code action} from {@code state}, if it exists. */
    public Optional<Transition> transition(String state, String action) {
        return transitions.stream()
                .filter(t -> t.from().equals(state) && t.action().equals(action))
                .findFirst();
    }

    public boolean hasState(String state) {
        return states.contains(state);
    }

    /** Distinct assignees that must be told about work waiting in {@code state}. */
    public List<Assignee> assigneesFor(String state) {
        return transitionsFrom(state).stream()
                .map(Transition::assignee)
                .distinct()
                .toList();
    }

    public static PlatformExceptions.Validation invalid(String message) {
        return new PlatformExceptions.Validation("Invalid workflow definition: " + message, java.util.Map.of());
    }

    /**
     * A single edge: from {@code from} to {@code to} on {@code action}.
     *
     * @param assignee who may take this action
     * @param slaHours optional time budget for the resulting task; {@code null} means no due date
     * @param outcome  how the instance ends when {@code to} is terminal; {@code null} means
     *                 {@link Outcome#COMPLETED}
     */
    public record Transition(
            String from,
            String to,
            String action,
            Assignee assignee,
            Integer slaHours,
            Outcome outcome) {

        public Transition {
            if (from == null || from.isBlank()) {
                throw invalid("a transition is missing 'from'");
            }
            if (to == null || to.isBlank()) {
                throw invalid("a transition is missing 'to'");
            }
            if (action == null || action.isBlank()) {
                throw invalid("a transition is missing 'action'");
            }
            if (action.length() > MAX_ACTION_LENGTH) {
                throw invalid("action '" + action + "' is longer than " + MAX_ACTION_LENGTH + " characters");
            }
            if (slaHours != null && slaHours < 0) {
                throw invalid("slaHours may not be negative");
            }
            assignee = assignee == null ? Assignee.anyone() : assignee;
        }
    }

    /**
     * Who the engine expects to act.
     *
     * <p>{@code ROLE} names a role code from the authorization catalogue
     * ({@code EDITOR}, {@code ORG_ADMIN}); {@code USER} names one principal; {@code ANY}
     * means "anyone who holds the transition permission".
     */
    public record Assignee(AssigneeType type, String id) {

        public Assignee {
            if (type == null) {
                throw invalid("an assignee is missing 'type'");
            }
            if (type != AssigneeType.ANY) {
                if (id == null || id.isBlank()) {
                    throw invalid("an assignee of type " + type + " is missing 'id'");
                }
                if (id.length() > MAX_ASSIGNEE_LENGTH) {
                    throw invalid("assignee id '" + id + "' is longer than " + MAX_ASSIGNEE_LENGTH + " characters");
                }
                if (type == AssigneeType.USER) {
                    // Checked here rather than where a task is created: a definition that names
                    // a non-UUID user is wrong the moment it is read, and finding that out when
                    // a task is opened makes it look like a task-creation bug.
                    try {
                        UUID.fromString(id);
                    } catch (IllegalArgumentException e) {
                        throw invalid("an assignee of type USER must name a user id, but '" + id
                                + "' is not a UUID");
                    }
                }
            } else if (id != null && !id.isBlank()) {
                throw invalid("an assignee of type ANY must not name an id");
            }
        }

        public static Assignee anyone() {
            return new Assignee(AssigneeType.ANY, null);
        }

        public static Assignee role(String code) {
            return new Assignee(AssigneeType.ROLE, code);
        }

        public static Assignee user(UUID principalId) {
            return new Assignee(AssigneeType.USER, principalId == null ? null : principalId.toString());
        }

        /**
         * Whether this principal may act.
         *
         * <p>Role membership is passed in rather than looked up here: the domain must not
         * know that authorization is a database. The caller resolves the principal's roles
         * once per request, which also means the engine cannot accidentally consult a
         * different scope for each transition.
         */
        public boolean permits(UUID principalId, List<String> roleCodes) {
            return switch (type) {
                case ANY -> true;
                case USER -> principalId != null && principalId.toString().equals(id);
                case ROLE -> roleCodes != null && roleCodes.contains(id);
            };
        }
    }

    public enum AssigneeType {
        ROLE,
        USER,
        ANY
    }

    /**
     * How an instance ends when it reaches a terminal state.
     *
     * <p>Only the terminal case matters: a transition whose target still has outgoing
     * transitions leaves the instance running. Without this, a flowchart whose "rejected"
     * state is terminal would report every rejection as a completion.
     */
    public enum Outcome {
        COMPLETED,
        REJECTED,
        CANCELLED
    }
}
