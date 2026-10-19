package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.State;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Transition;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.instance.md.service.MdSimpleFormDocumentLogic;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A live form as its automations read it (ADR-0046): the active version's fields, statuses and transitions. A formula
 * of an automation reads what a rule of the form reads — the shown fields, the lines, the number and the status — and
 * also the record's number and its last change ({@code recordNumber}, {@code modifiedAt}); a condition of a change
 * also reads the values before it ({@code old.<field>}) and whether it changed a field ({@code changed.<field>}). An
 * action writes only an entered field: shown, not computed, not a file.
 *
 * @param code     the entity code {@code studio.<name>}
 * @param archived the form is archived: its records are read-only and its automations wait
 */
public record AutomationForm(String code, Declaration declaration, boolean archived) {
    /** The prefix of a value before the change. */
    public static final String OLD = "old.";
    /** The prefix of whether the change changed a field. */
    public static final String CHANGED = "changed.";
    /** The record's number in its form. */
    public static final String RECORD_NUMBER = "recordNumber";
    /** The moment of the record's last change. */
    public static final String MODIFIED_AT = "modifiedAt";

    /** The names a formula over a record of the form reads, by type; with {@code change}, the old values too. */
    public Map<String, FormulaType> types(boolean change) {
        Workflow workflow = declaration.workflow();
        Map<String, FormulaType> header = MdSimpleFormDocumentLogic.header(
                declaration.fields(),
                declaration.lines(),
                workflow,
                declaration.entity().numbering());
        Map<String, FormulaType> types = new LinkedHashMap<>(header);
        types.put(RECORD_NUMBER, FormulaType.NUMBER);
        types.put(MODIFIED_AT, FormulaType.DATETIME);
        if (change) {
            header.forEach((name, type) -> {
                if (name.startsWith("lines.")) return;
                types.put(OLD + name, type);
                types.put(CHANGED + name, FormulaType.BOOL);
            });
        }
        return types;
    }

    /** The shown fields of the form by id. */
    public Map<String, Field> shown() {
        Map<String, Field> shown = new LinkedHashMap<>();
        for (Field field : declaration.fields()) {
            if (!Boolean.TRUE.equals(field.hidden())) shown.put(field.id(), field);
        }
        return shown;
    }

    /** The field an action may write, or null: shown, not computed, not a file. */
    public @Nullable Field entered(String id) {
        Field field = shown().get(id);
        if (field == null || field.formula() != null || "file".equals(field.type())) return null;
        return field;
    }

    /** Whether a save of a new record must name the field: required, without a default of its own. */
    public static boolean needsValue(Field field) {
        return field.required() && field.defaultValue() == null && field.defaultFormula() == null;
    }

    /** The shown user fields: whose users a notification may go to. */
    public List<String> userFields() {
        return shown().values().stream()
                .filter(field -> "user".equals(field.type()))
                .map(Field::id)
                .toList();
    }

    /** The statuses of the document by code, empty without statuses. */
    public Map<String, State> states() {
        Map<String, State> states = new LinkedHashMap<>();
        Workflow workflow = declaration.workflow();
        if (workflow != null) workflow.states().forEach(state -> states.put(state.code(), state));
        return states;
    }

    /** The transitions of the document by code, empty without statuses. */
    public Map<String, Transition> transitions() {
        Map<String, Transition> transitions = new LinkedHashMap<>();
        Workflow workflow = declaration.workflow();
        if (workflow != null) workflow.transitions().forEach(move -> transitions.put(move.code(), move));
        return transitions;
    }

    /** The form's name in {@code lang}. */
    public String title(String lang) {
        return text(declaration.entity().title(), lang);
    }

    /** The label of the choice {@code code} of a select field or of a status, or null for any other value. */
    public @Nullable String choice(String field, String code, String lang) {
        if (MdSimpleFormDocumentLogic.STATUS.equals(field)) {
            State state = states().get(code);
            return state == null ? null : text(state.labels(), lang);
        }
        Field declared = shown().get(field);
        if (declared == null || declared.options() == null) return null;
        for (Option option : Objects.requireNonNull(declared.options())) {
            if (option.code().equals(code)) return text(option.labels(), lang);
        }
        return null;
    }

    /** A text of the labels in {@code lang}, Russian when the language has none. */
    static String text(Labels labels, String lang) {
        String text = switch (lang) {
            case "uz" -> labels.uz();
            case "en" -> labels.en();
            default -> labels.ru();
        };
        return text == null || text.isBlank() ? labels.ru() : text;
    }
}
