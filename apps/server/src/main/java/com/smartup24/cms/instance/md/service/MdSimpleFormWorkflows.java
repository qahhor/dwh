package com.smartup24.cms.instance.md.service;

import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.bool;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.invalid;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.known;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.labels;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.required;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.text;

import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Lines;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.State;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Transition;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The statuses of a document of simple-form format 6 (ADR-0043): 2 to {@link #MAX_STATES} statuses — the first is
 * where a record starts and is no final one — each with its color, whether it is final and what it locks, and 1 to
 * {@link #MAX_TRANSITIONS} transitions, each a record action with a right of its own: from statuses that are not final
 * to another status, optionally only while a formula holds, with a comment none, optional or required, whether the
 * author of the record may take it and whether the people who may take the next step are told.
 */
final class MdSimpleFormWorkflows {
    static final int MAX_STATES = 20;
    static final int MAX_TRANSITIONS = 30;

    /** The colors of a status, as the screens draw its badge. */
    static final Set<String> COLORS = Set.of("gray", "blue", "green", "orange", "red", "purple");

    static final Set<String> COMMENTS = Set.of("none", "optional", "required");

    /** The rights of every live form: a transition is a right of its own and takes none of their names. */
    static final Set<String> ACTIONS = Set.of("view", "create", "update", "delete", "archive", "import");

    private static final String CODE = "[a-z][a-z0-9_]{0,19}";

    private MdSimpleFormWorkflows() {}

    /** The statuses and transitions of the form, or null when it names none. */
    static @Nullable Workflow workflow(JsonNode root, List<Field> fields, @Nullable List<Lines> lines) {
        if (!root.has("workflow")) return null;
        JsonNode workflow = required(root, "workflow", "workflow");
        known(workflow, Set.of("labels", "states", "transitions"), "workflow");
        Labels names = workflow.has("labels") ? labels(workflow, "labels", "workflow.labels") : null;
        Set<String> lockable = new LinkedHashSet<>();
        fields.stream().filter(field -> field.hidden() == null).forEach(field -> lockable.add(field.id()));
        if (lines != null) lines.forEach(table -> lockable.add(table.key()));
        List<State> states = states(required(workflow, "states", "workflow.states"), lockable);
        return new Workflow(
                names, states, transitions(required(workflow, "transitions", "workflow.transitions"), states));
    }

    /** The status that gives the number is one of the form's statuses; a number without statuses is given on create. */
    static void assignOn(@Nullable Numbering numbering, @Nullable Workflow workflow) {
        if (numbering == null || numbering.assignOn() == null) return;
        boolean known = workflow != null
                && workflow.states().stream().anyMatch(state -> state.code().equals(numbering.assignOn()));
        if (!known) throw invalid("entity.numbering.assignOn", "workflow");
    }

    private static List<State> states(JsonNode states, Set<String> lockable) {
        String path = "workflow.states";
        if (!states.isArray()) throw invalid(path, "structure");
        if (states.size() < 2 || states.size() > MAX_STATES) throw invalid(path, "limit");
        List<State> result = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int index = 0; index < states.size(); index++) {
            String at = path + "[" + index + "]";
            JsonNode state = states.get(index);
            known(state, Set.of("code", "labels", "color", "terminal", "lockAll", "locks"), at);
            String code = code(state, at);
            if (!codes.add(code)) throw invalid(at + ".code", "duplicate");
            String color = text(state, "color", at + ".color");
            if (!COLORS.contains(color)) throw invalid(at + ".color", "unsupported");
            Boolean terminal = flag(state, "terminal", at);
            // A record starts in the first status; a final one there would leave every new record read-only.
            if (index == 0 && terminal != null) throw invalid(at + ".terminal", "workflow");
            result.add(new State(
                    code,
                    labels(state, "labels", at + ".labels"),
                    color,
                    terminal,
                    flag(state, "lockAll", at),
                    locks(state, at, lockable)));
        }
        return List.copyOf(result);
    }

    private static @Nullable List<String> locks(JsonNode state, String at, Set<String> lockable) {
        if (!state.has("locks")) return null;
        JsonNode locks = required(state, "locks", at + ".locks");
        if (!locks.isArray()) throw invalid(at + ".locks", "structure");
        if (locks.isEmpty() || locks.size() > 50) throw invalid(at + ".locks", "limit");
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < locks.size(); index++) {
            String item = at + ".locks[" + index + "]";
            JsonNode key = locks.get(index);
            if (!key.isString()) throw invalid(item, "structure");
            if (!lockable.contains(key.asString())) throw invalid(item, "workflow");
            if (!result.add(key.asString())) throw invalid(item, "duplicate");
        }
        return List.copyOf(result);
    }

    private static List<Transition> transitions(JsonNode transitions, List<State> states) {
        String path = "workflow.transitions";
        if (!transitions.isArray()) throw invalid(path, "structure");
        if (transitions.isEmpty() || transitions.size() > MAX_TRANSITIONS) throw invalid(path, "limit");
        Set<String> leavable = new HashSet<>();
        Set<String> all = new HashSet<>();
        states.forEach(state -> {
            all.add(state.code());
            if (state.terminal() == null) leavable.add(state.code());
        });
        List<Transition> result = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int index = 0; index < transitions.size(); index++) {
            String at = path + "[" + index + "]";
            JsonNode transition = transitions.get(index);
            known(
                    transition,
                    Set.of("code", "labels", "from", "to", "condition", "comment", "notAuthor", "notify"),
                    at);
            String code = code(transition, at);
            if (ACTIONS.contains(code)) throw invalid(at + ".code", "identifier");
            if (!codes.add(code)) throw invalid(at + ".code", "duplicate");
            List<String> from = from(required(transition, "from", at + ".from"), at + ".from", leavable);
            String to = text(transition, "to", at + ".to");
            if (!all.contains(to) || from.contains(to)) throw invalid(at + ".to", "workflow");
            String comment = text(transition, "comment", at + ".comment");
            if (!COMMENTS.contains(comment)) throw invalid(at + ".comment", "unsupported");
            result.add(new Transition(
                    code,
                    labels(transition, "labels", at + ".labels"),
                    from,
                    to,
                    MdSimpleFormLogic.formula(transition, "condition", at + ".condition"),
                    comment,
                    flag(transition, "notAuthor", at),
                    flag(transition, "notify", at)));
        }
        return List.copyOf(result);
    }

    private static List<String> from(JsonNode from, String at, Set<String> leavable) {
        if (!from.isArray()) throw invalid(at, "structure");
        if (from.isEmpty() || from.size() > MAX_STATES) throw invalid(at, "limit");
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < from.size(); index++) {
            String item = at + "[" + index + "]";
            JsonNode code = from.get(index);
            if (!code.isString()) throw invalid(item, "structure");
            if (!leavable.contains(code.asString())) throw invalid(item, "workflow");
            if (!result.add(code.asString())) throw invalid(item, "duplicate");
        }
        return List.copyOf(result);
    }

    private static String code(JsonNode owner, String at) {
        String code = text(owner, "code", at + ".code");
        if (!code.matches(CODE)) throw invalid(at + ".code", "identifier");
        return code;
    }

    /** A flag present only when true, as the stored form keeps it. */
    private static @Nullable Boolean flag(JsonNode owner, String key, String at) {
        if (!owner.has(key)) return null;
        if (!bool(owner, key, at + "." + key)) throw invalid(at + "." + key, "structure");
        return Boolean.TRUE;
    }
}
