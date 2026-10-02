package com.smartup24.cms.instance.common.entity.workflow;

import com.smartup24.cms.instance.common.entity.hook.EntityRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The states of a document and the transitions between them (ADR-0032, 9.2; plan 10/10, item 5.7). The status field
 * is a read-only select whose options are the states and whose default is the initial one: it changes only by a
 * transition, a record action of the general runtime ({@code POST …/{id}/actions/<transition>} with If-Match). A
 * transition from a state it does not leave answers 422 {@code entity_transition_not_allowed}; a state may lock fields
 * and collections, and a terminal one locks the whole record. The states are code, not data: statuses an administrator
 * keeps are a reference entity (ADR-0032, 8).
 *
 * <pre>{@code
 * EntityWorkflow.on("status")
 *         .state("draft", "example.orders.status.draft").initial()
 *         .state("posted", "example.orders.status.posted").locks("lines", "customer", "currency")
 *         .state("cancelled", "example.orders.status.cancelled").terminal()
 *         .transition("post", "draft", "posted").permission("post").rule(Rules.hasRows("lines"))
 *         .transition("unpost", "posted", "draft").permission("unpost")
 *         .transition("cancel", "draft", "cancelled").permission("update").confirm("example.orders.cancel_confirm")
 *         .build();
 * }</pre>
 */
public final class EntityWorkflow {

    private final String field;
    private final Map<String, EntityState> states;
    private final Map<String, EntityTransition> transitions;

    private EntityWorkflow(String field, List<EntityState> states, List<EntityTransition> transitions) {
        this.field = Objects.requireNonNull(field, "field");
        this.states = new LinkedHashMap<>();
        for (EntityState state : states) {
            if (this.states.put(state.code(), state) != null) {
                throw new IllegalArgumentException("Workflow on " + field + ": duplicate state " + state.code());
            }
        }
        if (states.stream().filter(EntityState::initial).count() != 1) {
            throw new IllegalArgumentException("Workflow on " + field + ": exactly one initial state");
        }
        this.transitions = new LinkedHashMap<>();
        for (EntityTransition transition : transitions) {
            if (this.transitions.put(transition.code(), transition) != null) {
                throw new IllegalArgumentException(
                        "Workflow on " + field + ": duplicate transition " + transition.code());
            }
            Set<String> named = new HashSet<>(transition.from());
            named.add(transition.to());
            if (!this.states.keySet().containsAll(named)) {
                throw new IllegalArgumentException(
                        "Workflow on " + field + ": transition " + transition.code() + " names an unknown state");
            }
            if (transition.from().stream().map(this.states::get).anyMatch(from -> from != null && from.terminal())) {
                throw new IllegalArgumentException(
                        "Workflow on " + field + ": transition " + transition.code() + " leaves a terminal state");
            }
        }
    }

    /** A process kept in the select field {@code statusField}. */
    public static Builder on(String statusField) {
        return new Builder(statusField);
    }

    /** The key of the status field. */
    public String field() {
        return field;
    }

    /** The states, in declaration order. */
    public List<EntityState> states() {
        return List.copyOf(states.values());
    }

    /** The transitions, in declaration order. */
    public List<EntityTransition> transitions() {
        return List.copyOf(transitions.values());
    }

    public Optional<EntityState> state(String code) {
        return Optional.ofNullable(states.get(code));
    }

    public Optional<EntityTransition> transition(String code) {
        return Optional.ofNullable(transitions.get(code));
    }

    /** The state a new record starts in. */
    public EntityState initial() {
        return states.values().stream().filter(EntityState::initial).findFirst().orElseThrow();
    }

    /** Whether {@code transition} leaves the state {@code state}. */
    public boolean allows(String transition, @Nullable String state) {
        EntityTransition declared = transitions.get(transition);
        return declared != null && state != null && declared.from().contains(state);
    }

    /**
     * The keys a save cannot change in the state {@code state}: the state's locks, or — in a terminal state — every
     * key of {@code all} (the fields of the form and the collections).
     */
    public Set<String> locked(@Nullable String state, Set<String> all) {
        EntityState current = state == null ? null : states.get(state);
        if (current == null) return Set.of();
        return current.terminal() ? Set.copyOf(all) : current.locks();
    }

    /** Collects a process; each modifier changes the state or the transition declared last. */
    public static final class Builder {

        private final String field;
        private final List<StateDraft> states = new ArrayList<>();
        private final List<TransitionDraft> transitions = new ArrayList<>();

        private Builder(String field) {
            this.field = field;
        }

        public Builder state(String code, String labelKey) {
            states.add(new StateDraft(code, labelKey));
            return this;
        }

        /** The state declared last is the one a new record starts in. */
        public Builder initial() {
            lastState().initial = true;
            return this;
        }

        /** The state declared last leaves the record only to be read. */
        public Builder terminal() {
            lastState().terminal = true;
            return this;
        }

        /** The fields and collections a save cannot change in the state declared last. */
        public Builder locks(String... keys) {
            lastState().locks.addAll(List.of(keys));
            return this;
        }

        /** A transition that moves the record from {@code from} to {@code to}; its right is its code by default. */
        public Builder transition(String code, String from, String to) {
            transitions.add(new TransitionDraft(code, from, to));
            return this;
        }

        /** More states the transition declared last leaves. */
        public Builder from(String... more) {
            lastTransition().from.addAll(List.of(more));
            return this;
        }

        /** The action of the entity's right the transition declared last needs. */
        public Builder permission(String action) {
            lastTransition().permission = action;
            return this;
        }

        /** A rule the record meets before the transition declared last (ADR-0032, 6.6). */
        public Builder rule(EntityRule rule) {
            lastTransition().rules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /** The dictionary key of the question the screen asks before the transition declared last. */
        public Builder confirm(String questionKey) {
            lastTransition().confirmKey = questionKey;
            return this;
        }

        public EntityWorkflow build() {
            return new EntityWorkflow(
                    field,
                    states.stream()
                            .map(s -> new EntityState(s.code, s.labelKey, s.initial, s.terminal, s.locks))
                            .toList(),
                    transitions.stream()
                            .map(t -> new EntityTransition(t.code, t.from, t.to, t.permission, t.confirmKey, t.rules))
                            .toList());
        }

        private StateDraft lastState() {
            if (states.isEmpty()) throw new IllegalStateException("Declare a state first");
            return states.getLast();
        }

        private TransitionDraft lastTransition() {
            if (transitions.isEmpty()) throw new IllegalStateException("Declare a transition first");
            return transitions.getLast();
        }
    }

    private static final class StateDraft {
        private final String code;
        private final String labelKey;
        private boolean initial;
        private boolean terminal;
        private final Set<String> locks = new LinkedHashSet<>();

        private StateDraft(String code, String labelKey) {
            this.code = code;
            this.labelKey = labelKey;
        }
    }

    private static final class TransitionDraft {
        private final String code;
        private final Set<String> from = new LinkedHashSet<>();
        private final String to;
        private String permission;
        private @Nullable String confirmKey;
        private final List<EntityRule> rules = new ArrayList<>();

        private TransitionDraft(String code, String from, String to) {
            this.code = code;
            this.from.add(from);
            this.to = to;
            this.permission = code;
        }
    }
}
