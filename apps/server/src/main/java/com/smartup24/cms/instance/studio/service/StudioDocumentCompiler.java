package com.smartup24.cms.instance.studio.service;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.*;

import com.smartup24.cms.instance.common.entity.EntityProcessView;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.LineField;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Lines;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.State;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Transition;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Target;
import com.smartup24.cms.instance.md.service.MdSimpleFormDocumentLogic;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityFields.Builder;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.workflow.EntityWorkflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The document parts of a live form of format 6 compiled into the platform's own (ADR-0043): the number — a column the
 * server fills, searched and sorted as text — the status, a read-only choice of the states that starts in the first one,
 * the comment of the last transition, each table of lines a collection of the general runtime whose rows live in the
 * table of its place ({@code studio_record_lines_<1..3>}) as JSON properties, and the statuses an
 * {@link EntityWorkflow} whose transitions are record actions with a right each, guarded by their condition and, when
 * the form says so, refused to the record's author.
 */
final class StudioDocumentCompiler {
    /** The table of the rows of the table of lines at {@code place} (1 to 3) of every form (V227). */
    static final String LINES_TABLE = "studio_record_lines_";

    private StudioDocumentCompiler() {}

    /** The document's number: written by the server, never by a client; searched and sorted as text. */
    static Builder numberField(Numbering numbering, String prefix) {
        return text(MdSimpleFormDocumentLogic.NUMBER, numberLabel(numbering, prefix))
                .column("doc_number")
                .readonly()
                .list(sortable().searchable());
    }

    /**
     * The status: a read-only choice of the states that starts in the first one; filtered in the list. Its property of
     * the payload has an underscore, which no declared field id has.
     */
    static Builder status(Workflow workflow, String prefix) {
        List<String> codes = workflow.states().stream().map(State::code).toList();
        return select(MdSimpleFormDocumentLogic.STATUS, statusLabel(workflow, prefix), codes, statePrefix(prefix))
                .jsonProperty("payload", "doc_status")
                .readonly()
                .defaultValue(FieldDefault.fixed(codes.getFirst()));
    }

    /** The comment of the record's last transition, kept until the next one and shown in its history. */
    static Builder comment() {
        return textarea(MdSimpleFormDocumentLogic.COMMENT, "studio.record.doc_comment")
                .jsonProperty("payload", "doc_comment")
                .readonly()
                .length(null, StudioDocumentHooks.MAX_COMMENT)
                .list(hidden());
    }

    /** The tables of lines as collections, each in the table of its place. */
    static List<EntityCollection> collections(Declaration source, String prefix, Function<String, String> titles) {
        List<Lines> lines = source.lines();
        if (lines == null) return List.of();
        List<EntityCollection> collections = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            Lines table = lines.get(index);
            int place = index + 1;
            EntityCollection.Builder collection = EntityCollection.of(table.key(), linesKey(prefix, table.key()))
                    .table(LINES_TABLE + place, "l" + place)
                    .parentColumn("record_id")
                    .positionColumn("position")
                    .maxRows(table.maxRows());
            for (LineField column : table.fields()) collection.field(column(table, column, prefix, titles));
            collections.add(collection.build());
        }
        return List.copyOf(collections);
    }

    private static Builder column(Lines table, LineField column, String prefix, Function<String, String> titles) {
        String key = column.id();
        String label = columnKey(prefix, table.key(), key);
        Builder builder = switch (column.type()) {
            case "text" -> text(key, label);
            case "textarea" -> textarea(key, label);
            case "number" -> number(key, label);
            case "date" -> date(key, label);
            case "datetime" -> instant(key, label);
            case "time" -> time(key, label);
            case "bool" -> bool(key, label);
            case "email" -> email(key, label);
            case "phone" -> phone(key, label);
            case "url" -> url(key, label);
            case "select" -> select(key, label, codes(column.options()), columnOptionPrefix(prefix, table.key(), key));
            case "user" -> ref(key, label).target(StudioEntityCompiler.USERS, "name");
            case "ref" -> reference(key, label, Objects.requireNonNull(column.target(), key), titles);
            default -> throw new IllegalArgumentException("Unsupported live line field type");
        };
        builder.jsonProperty("payload", key);
        if (column.required()) builder.required();
        if (column.maxLength() != null) builder.length(null, column.maxLength());
        if (column.scale() != null) builder.scale(column.scale());
        // A computed column is written by the server on every save of its row, never by a client (ADR-0043).
        if (column.formula() != null) builder.readonly();
        return builder;
    }

    private static Builder reference(String key, String label, Target target, Function<String, String> titles) {
        return "list".equals(target.kind())
                ? enumeration(key, label, StudioListEnums.reference(target.code()))
                : ref(key, label).target(target.code(), titles.apply(target.code()));
    }

    /**
     * The process: the states in their order, the first where a record starts, a final one only read, each locking the
     * fields and tables it names or, with {@code lockAll}, all of them; each transition with its right (its code), its
     * condition and the rule that the author may not take it.
     */
    static EntityWorkflow workflow(
            Workflow workflow, String prefix, Set<String> lockable, Map<String, FormulaType> header) {
        EntityWorkflow.Builder process = EntityWorkflow.on(MdSimpleFormDocumentLogic.STATUS);
        boolean first = true;
        for (State state : workflow.states()) {
            process.state(state.code(), statePrefix(prefix) + state.code());
            if (first) process.initial();
            first = false;
            if (Boolean.TRUE.equals(state.terminal())) process.terminal();
            Set<String> locks = new LinkedHashSet<>();
            if (Boolean.TRUE.equals(state.lockAll())) locks.addAll(lockable);
            if (state.locks() != null) locks.addAll(state.locks());
            if (!locks.isEmpty()) process.locks(locks.toArray(String[]::new));
        }
        for (Transition transition : workflow.transitions()) {
            process.transition(transition.code(), transition.from().getFirst(), transition.to());
            if (transition.from().size() > 1) {
                process.from(
                        transition.from().subList(1, transition.from().size()).toArray(String[]::new));
            }
            if (transition.condition() != null) {
                process.rule(new StudioTransitionRules.Condition(
                        transition.code(), transition.labels(), Formula.compile(transition.condition(), header)));
            }
            if (Boolean.TRUE.equals(transition.notAuthor())) {
                process.rule(new StudioTransitionRules.NotAuthor(transition.code(), transition.labels()));
            }
        }
        return process.build();
    }

    /**
     * The statuses and transitions of a form whose rights are kept while it is not served (ADR-0038, decision 10):
     * without locks and conditions, which read fields that may no longer compile.
     */
    static Workflow rightsOnly(Workflow workflow) {
        return new Workflow(
                workflow.labels(),
                workflow.states().stream()
                        .map(state ->
                                new State(state.code(), state.labels(), state.color(), state.terminal(), null, null))
                        .toList(),
                workflow.transitions().stream()
                        .map(transition -> new Transition(
                                transition.code(),
                                transition.labels(),
                                transition.from(),
                                transition.to(),
                                null,
                                transition.comment(),
                                transition.notAuthor(),
                                transition.notifies()))
                        .toList());
    }

    /** The rights of the form: those of every live form and one per transition, named by the transition. */
    static Map<String, String> rights(Declaration source, String prefix, Map<String, String> common) {
        Map<String, String> rights = new LinkedHashMap<>(common);
        if (source.workflow() != null) {
            source.workflow()
                    .transitions()
                    .forEach(transition -> rights.put(transition.code(), transitionKey(prefix, transition.code())));
        }
        return rights;
    }

    /** What the screens show of the process beyond the declaration: colors, names and comments of transitions. */
    static @Nullable EntityProcessView view(Declaration source, String prefix) {
        Workflow workflow = source.workflow();
        if (workflow == null) return null;
        Map<String, String> colors = new LinkedHashMap<>();
        workflow.states().forEach(state -> colors.put(state.code(), state.color()));
        Map<String, EntityProcessView.Transition> transitions = new LinkedHashMap<>();
        workflow.transitions()
                .forEach(transition -> transitions.put(
                        transition.code(),
                        new EntityProcessView.Transition(
                                transitionKey(prefix, transition.code()), transition.comment())));
        return new EntityProcessView(colors, transitions);
    }

    /** The labels of the document parts, by their dictionary keys. */
    static void messages(Declaration source, String prefix, BiConsumer<String, Labels> labelled) {
        Numbering numbering = source.entity().numbering();
        if (numbering != null && numbering.labels() != null)
            labelled.accept(numberLabel(numbering, prefix), numbering.labels());
        Workflow workflow = source.workflow();
        if (workflow != null) {
            if (workflow.labels() != null) labelled.accept(statusLabel(workflow, prefix), workflow.labels());
            workflow.states().forEach(state -> labelled.accept(statePrefix(prefix) + state.code(), state.labels()));
            workflow.transitions()
                    .forEach(transition ->
                            labelled.accept(transitionKey(prefix, transition.code()), transition.labels()));
        }
        if (source.lines() == null) return;
        for (Lines table : source.lines()) {
            labelled.accept(linesKey(prefix, table.key()), table.labels());
            for (LineField column : table.fields()) {
                labelled.accept(columnKey(prefix, table.key(), column.id()), column.labels());
                if (column.options() == null) continue;
                for (Option option : column.options()) {
                    labelled.accept(
                            columnOptionPrefix(prefix, table.key(), column.id()) + option.code(), option.labels());
                }
            }
        }
    }

    /** The keys a state may lock: the shown fields and the tables of lines. */
    static Set<String> lockable(List<Field> shown, @Nullable List<Lines> lines) {
        Set<String> keys = new LinkedHashSet<>();
        shown.forEach(field -> keys.add(field.id()));
        if (lines != null) lines.forEach(table -> keys.add(table.key()));
        return keys;
    }

    static String linesKey(String prefix, String table) {
        return prefix + ".line." + table;
    }

    static String transitionKey(String prefix, String code) {
        return prefix + ".transition." + code;
    }

    /** The prefix of the names of the states: the options of the status field. */
    static String statePrefix(String prefix) {
        return prefix + ".option." + MdSimpleFormDocumentLogic.STATUS + ".";
    }

    private static String columnKey(String prefix, String table, String column) {
        return linesKey(prefix, table) + ".field." + column;
    }

    private static String columnOptionPrefix(String prefix, String table, String column) {
        return linesKey(prefix, table) + ".option." + column + ".";
    }

    private static String numberLabel(Numbering numbering, String prefix) {
        return numbering.labels() == null
                ? "studio.record.doc_number"
                : prefix + ".field." + MdSimpleFormDocumentLogic.NUMBER;
    }

    private static String statusLabel(Workflow workflow, String prefix) {
        return workflow.labels() == null
                ? "studio.record.doc_status"
                : prefix + ".field." + MdSimpleFormDocumentLogic.STATUS;
    }

    private static List<String> codes(@Nullable List<Option> options) {
        return Objects.requireNonNull(options, "A live select has its choices").stream()
                .map(Option::code)
                .toList();
    }
}
