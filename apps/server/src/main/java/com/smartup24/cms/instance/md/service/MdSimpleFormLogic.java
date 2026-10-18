package com.smartup24.cms.instance.md.service;

import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.invalid;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.known;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.required;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.text;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaException;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormRuleDtos.Rule;
import com.smartup24.cms.instance.md.api.MdSimpleFormRuleDtos.RuleMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The parts format 5 of a simple form adds (ADR-0042): the formulas of a field — its computed value, its default, when
 * it is shown, required or read-only — and the validation rules of the form. Every formula is parsed and checked here
 * against the shown fields of the form, so a form that is stored is a form whose formulas evaluate: a problem answers
 * 422 at the formula's address ({@code fields[2].formula}, {@code rules[0].formula}) with the reason and the position.
 * The computed fields may not read each other in a cycle; a hidden field's formulas are kept but not served.
 */
public final class MdSimpleFormLogic {
    /** The keys of a field's formulas, in the order they are checked. */
    static final List<String> KEYS = List.of("formula", "defaultFormula", "visibleIf", "requiredIf", "readonlyIf");

    /** Rules of one form. */
    static final int MAX_RULES = 20;

    static final Set<String> COMPUTED = Set.of("text", "textarea", "number", "date", "datetime", "time", "bool");
    private static final Set<String> DEFAULTED =
            Set.of("text", "textarea", "number", "date", "datetime", "time", "bool", "select", "email", "phone", "url");
    private static final String RULE_KEY = "[a-z][a-z0-9_]{0,19}";

    private MdSimpleFormLogic() {}

    /** Whether the field has a formula of format 5. */
    public static boolean hasLogic(Field field) {
        return field.formula() != null
                || field.defaultFormula() != null
                || field.visibleIf() != null
                || field.requiredIf() != null
                || field.readonlyIf() != null;
    }

    /** The formula types of the fields a formula of the form may read: its shown fields, by id. */
    public static Map<String, FormulaType> types(List<Field> fields) {
        Map<String, FormulaType> types = new LinkedHashMap<>();
        for (Field field : fields) {
            if (!Boolean.TRUE.equals(field.hidden())) types.put(field.id(), type(field));
        }
        return types;
    }

    /** The formula type of a field's value: a text, a number, a date..., a code of a choice or a list, a row's key. */
    public static FormulaType type(Field field) {
        return switch (field.type()) {
            case "number" -> FormulaType.NUMBER;
            case "date" -> FormulaType.DATE;
            case "datetime" -> FormulaType.DATETIME;
            case "time" -> FormulaType.TIME;
            case "bool" -> FormulaType.BOOL;
            case "select" -> FormulaType.OPTION;
            case "ref" ->
                field.target() != null && "list".equals(field.target().kind()) ? FormulaType.OPTION : FormulaType.REF;
            case "user" -> FormulaType.REF;
            default -> FormulaType.TEXT;
        };
    }

    /** Whether a formula of {@code type} gives a value a field of {@code fieldType} keeps. */
    public static boolean fits(String fieldType, FormulaType type) {
        if (type == FormulaType.NULL) return true;
        return switch (fieldType) {
            case "number" -> type == FormulaType.NUMBER;
            case "date" -> type == FormulaType.DATE;
            case "datetime" -> type == FormulaType.DATETIME;
            case "time" -> type == FormulaType.TIME;
            case "bool" -> type == FormulaType.BOOL;
            default -> type == FormulaType.TEXT || type == FormulaType.OPTION;
        };
    }

    /** A formula's text of a field or rule: 1 to 1000 characters without control characters but line breaks and tabs. */
    static @Nullable String formula(JsonNode owner, String key, String at) {
        if (!owner.has(key)) return null;
        String value = text(owner, key, at);
        if (value.isBlank()
                || value.length() > Formula.MAX_LENGTH
                || value.codePoints()
                        .anyMatch(point -> (Character.isISOControl(point) && point != '\n' && point != '\t')
                                || Character.getType(point) == Character.FORMAT
                                || (point >= 0xd800 && point <= 0xdfff))) {
            throw invalid(at, "formula");
        }
        return value;
    }

    /** The rules of the form, or null when it names none. */
    static @Nullable List<Rule> rules(JsonNode root, List<Field> fields) {
        if (!root.has("rules")) return null;
        JsonNode rules = required(root, "rules", "rules");
        if (!rules.isArray()) throw invalid("rules", "structure");
        if (rules.isEmpty() || rules.size() > MAX_RULES) throw invalid("rules", "limit");
        Set<String> shown = types(fields).keySet();
        Set<String> keys = new HashSet<>();
        List<Rule> result = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            String at = "rules[" + index + "]";
            JsonNode rule = rules.get(index);
            known(rule, Set.of("key", "formula", "message", "field"), at);
            String key = text(rule, "key", at + ".key");
            if (!key.matches(RULE_KEY)) throw invalid(at + ".key", "identifier");
            if (!keys.add(key)) throw invalid(at + ".key", "duplicate");
            String formula = formula(rule, "formula", at + ".formula");
            if (formula == null) throw invalid(at + ".formula", "missing");
            String field = rule.has("field") ? text(rule, "field", at + ".field") : null;
            if (field != null && !shown.contains(field)) throw invalid(at + ".field", "unsupported");
            result.add(new Rule(key, formula, message(required(rule, "message", at + ".message"), at), field));
        }
        return List.copyOf(result);
    }

    private static RuleMessage message(JsonNode message, String at) {
        String path = at + ".message";
        known(message, Set.of("ru", "uz", "en"), path);
        return new RuleMessage(line(message, "ru", path), line(message, "uz", path), line(message, "en", path));
    }

    private static String line(JsonNode message, String language, String at) {
        String text = text(message, language, at + "." + language);
        boolean fits = !text.isBlank()
                && text.codePointCount(0, text.length()) <= 200
                && text.strip().equals(text)
                && text.codePoints()
                        .noneMatch(point -> Character.isISOControl(point)
                                || Character.getType(point) == Character.FORMAT
                                || (point >= 0xd800 && point <= 0xdfff));
        if (!fits) throw invalid(at + "." + language, "label");
        return text;
    }

    /**
     * Checks every formula of the form: the place of each (a computed field is read-only and nothing else, a default
     * formula replaces a fixed default, {@code requiredIf} makes an optional field required), its type against its
     * field or yes/no, no field shown by its own value, no cycle among the computed fields. 422 naming every problem.
     */
    static void check(List<Field> fields, @Nullable List<Rule> rules) {
        List<FieldErrorItem> problems = problems(fields, rules, types(fields));
        if (!problems.isEmpty()) throw ApiException.validation("error.md.simple_form_invalid", problems);
    }

    /**
     * The problems of every formula of the fields and rules, checked against {@code types}: the shown fields and, for a
     * document of format 6, its number, its status and its rows (ADR-0043).
     */
    static List<FieldErrorItem> problems(
            List<Field> fields, @Nullable List<Rule> rules, Map<String, FormulaType> types) {
        List<FieldErrorItem> problems = new ArrayList<>();
        Map<String, Formula> computed = new LinkedHashMap<>();
        Map<String, Integer> places = new LinkedHashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            if (Boolean.TRUE.equals(field.hidden()) || !hasLogic(field)) continue;
            String at = "fields[" + index + "]";
            place(field, at, problems);
            Formula value = checked(field.formula(), types, at + ".formula", field.type(), problems);
            if (value != null) {
                computed.put(field.id(), value);
                places.put(field.id(), index);
            }
            checked(field.defaultFormula(), types, at + ".defaultFormula", field.type(), problems);
            Formula visible = checked(field.visibleIf(), types, at + ".visibleIf", null, problems);
            if (visible != null && visible.fields().containsKey(field.id())) {
                problems.add(new FormulaException(
                                "self", Objects.requireNonNull(visible.fields().get(field.id())), field.id())
                        .fieldError(at + ".visibleIf"));
            }
            checked(field.requiredIf(), types, at + ".requiredIf", null, problems);
            checked(field.readonlyIf(), types, at + ".readonlyIf", null, problems);
        }
        cycles(computed, id -> "fields[" + places.get(id) + "].formula", problems);
        if (rules != null) {
            for (int index = 0; index < rules.size(); index++) {
                checked(rules.get(index).formula(), types, "rules[" + index + "].formula", null, problems);
            }
        }
        return problems;
    }

    /** The outcome of checking one formula: its type when it is accepted, else its problem. */
    public record Checked(
            @Nullable FormulaType type, @Nullable FormulaException problem) {}

    /**
     * Checks one formula as the editor types it: against the fields it may read, giving the type of the field it
     * computes or defaults ({@code fieldType}) or a yes/no when that is null; {@code self} is the field a condition
     * shows, which it may not read. Cycles among computed fields are checked with the whole form.
     */
    public static Checked check(
            String source, Map<String, FormulaType> types, @Nullable String fieldType, @Nullable String self) {
        try {
            Formula formula = Formula.compile(source, types);
            boolean fits = fieldType == null
                    ? formula.type() == FormulaType.BOOL || formula.type() == FormulaType.NULL
                    : fits(fieldType, formula.type());
            if (!fits)
                return new Checked(
                        null, new FormulaException("result", 1, formula.type().wire()));
            Integer read = self == null ? null : formula.fields().get(self);
            if (read != null) return new Checked(null, new FormulaException("self", read, self));
            return new Checked(formula.type(), null);
        } catch (FormulaException problem) {
            return new Checked(null, problem);
        }
    }

    /** The problems of where a field's formulas stand, addressed to the formula that may not stand there. */
    private static void place(Field field, String at, List<FieldErrorItem> problems) {
        if (field.formula() != null) {
            if (!COMPUTED.contains(field.type())) problems.add(logic(at + ".formula"));
            else if (field.required()
                    || field.defaultValue() != null
                    || field.defaultFormula() != null
                    || field.visibleIf() != null
                    || field.requiredIf() != null
                    || field.readonlyIf() != null) problems.add(logic(at + ".formula"));
        }
        if (field.defaultFormula() != null && (!DEFAULTED.contains(field.type()) || field.defaultValue() != null)) {
            problems.add(logic(at + ".defaultFormula"));
        }
        if (field.requiredIf() != null && field.required()) problems.add(logic(at + ".requiredIf"));
    }

    static FieldErrorItem logic(String at) {
        return FieldErrorItem.keyed(at, "simple_form_logic", "error.md.simple_form_logic");
    }

    /**
     * The checked formula, or null with its problem added: a formula of a field's value gives that field's type, any
     * other a yes/no.
     */
    static @Nullable Formula checked(
            @Nullable String source,
            Map<String, FormulaType> types,
            String at,
            @Nullable String fieldType,
            List<FieldErrorItem> problems) {
        if (source == null) return null;
        try {
            Formula formula = Formula.compile(source, types);
            boolean fits = fieldType == null
                    ? formula.type() == FormulaType.BOOL || formula.type() == FormulaType.NULL
                    : fits(fieldType, formula.type());
            if (!fits) {
                problems.add(new FormulaException("result", 1, formula.type().wire()).fieldError(at));
                return null;
            }
            return formula;
        } catch (FormulaException problem) {
            problems.add(problem.fieldError(at));
            return null;
        }
    }

    /** A read of one computed field by another. */
    private record Edge(String reader, String read) {}

    /**
     * A computed field that reads itself through other computed fields: the read that closes the first cycle, addressed
     * to the formula of the reader ({@code at}).
     */
    static void cycles(Map<String, Formula> computed, Function<String, String> at, List<FieldErrorItem> problems) {
        Set<String> done = new HashSet<>();
        for (String start : computed.keySet()) {
            if (done.contains(start)) continue;
            Edge edge = cycle(start, computed, new HashSet<>(), done);
            if (edge == null) continue;
            int position = Objects.requireNonNull(
                    Objects.requireNonNull(computed.get(edge.reader())).fields().get(edge.read()));
            problems.add(new FormulaException("cycle", position, edge.read()).fieldError(at.apply(edge.reader())));
            return;
        }
    }

    /** The read that closes a cycle reached from {@code field}, or null when none is. */
    private static @Nullable Edge cycle(
            String field, Map<String, Formula> computed, Set<String> path, Set<String> done) {
        path.add(field);
        for (String read : Objects.requireNonNull(computed.get(field)).fields().keySet()) {
            if (!computed.containsKey(read) || done.contains(read)) continue;
            if (path.contains(read)) return new Edge(field, read);
            Edge found = cycle(read, computed, path, done);
            if (found != null) return found;
        }
        path.remove(field);
        done.add(field);
        return null;
    }
}
