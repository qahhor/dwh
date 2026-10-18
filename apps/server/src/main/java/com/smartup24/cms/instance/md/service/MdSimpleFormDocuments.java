package com.smartup24.cms.instance.md.service;

import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.bool;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.integer;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.invalid;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.known;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.labels;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.required;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.text;

import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.LineField;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Lines;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The document parts format 6 of a simple form adds (ADR-0043): the tables of its lines — up to {@link #MAX_TABLES},
 * each of 1 to {@link #MAX_COLUMNS} columns of the field types and at most {@link #MAX_ROWS} rows — and the number of
 * its records. The statuses are read by {@link MdSimpleFormWorkflows}; the formulas of all parts are checked by
 * {@link MdSimpleFormDocumentLogic}. Here only the shape, so a stored form is one the runtime can serve.
 */
final class MdSimpleFormDocuments {
    /** Tables of lines of one form: each keeps its rows in a table of its own, by its place (ADR-0043). */
    static final int MAX_TABLES = 3;

    static final int MAX_COLUMNS = 20;

    /** The most rows of a table, as a collection of the platform takes them (ADR-0032, 19, question 4). */
    static final int MAX_ROWS = 500;

    /** The keys of a row the platform keeps itself. */
    private static final Set<String> ROW_KEYS = Set.of("id", "position");

    private static final Set<String> LINE_FIELD =
            Set.of("id", "type", "labels", "required", "maxLength", "scale", "options", "target", "formula");
    private static final Set<String> LINE_TYPES = Set.of(
            "text",
            "textarea",
            "number",
            "date",
            "datetime",
            "time",
            "bool",
            "email",
            "phone",
            "url",
            "select",
            "ref",
            "user");
    private static final String KEY = "[a-z][a-z0-9]{0,19}";

    /** A token of a number's pattern: the year, its last two digits, the month or the counter of 1 to 12 digits. */
    private static final Pattern TOKEN = Pattern.compile("\\{(YYYY|YY|MM|N{1,12})}");

    private static final Set<String> RESETS = Set.of("never", "year", "month");

    private MdSimpleFormDocuments() {}

    /** The tables of lines, or null when the form names none; their keys are no field's and reserve no word. */
    static @Nullable List<Lines> lines(JsonNode root, List<Field> fields, Predicate<String> reserved) {
        if (!root.has("lines")) return null;
        JsonNode tables = required(root, "lines", "lines");
        if (!tables.isArray()) throw invalid("lines", "structure");
        if (tables.isEmpty() || tables.size() > MAX_TABLES) throw invalid("lines", "limit");
        Set<String> taken = new HashSet<>();
        fields.forEach(field -> taken.add(field.id()));
        List<Lines> result = new ArrayList<>();
        for (int index = 0; index < tables.size(); index++) {
            String at = "lines[" + index + "]";
            JsonNode table = tables.get(index);
            known(table, Set.of("key", "labels", "maxRows", "fields"), at);
            String key = text(table, "key", at + ".key");
            if (!key.matches(KEY) || reserved.test(key)) throw invalid(at + ".key", "identifier");
            if (!taken.add(key)) throw invalid(at + ".key", "duplicate");
            result.add(new Lines(
                    key,
                    labels(table, "labels", at + ".labels"),
                    integer(table, "maxRows", at + ".maxRows", 1, MAX_ROWS),
                    columns(required(table, "fields", at + ".fields"), at + ".fields")));
        }
        return List.copyOf(result);
    }

    private static List<LineField> columns(JsonNode columns, String at) {
        if (!columns.isArray()) throw invalid(at, "structure");
        if (columns.isEmpty() || columns.size() > MAX_COLUMNS) throw invalid(at, "limit");
        List<LineField> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < columns.size(); index++) {
            String item = at + "[" + index + "]";
            JsonNode column = columns.get(index);
            known(column, LINE_FIELD, item);
            String id = text(column, "id", item + ".id");
            if (!id.matches(KEY) || ROW_KEYS.contains(id)) throw invalid(item + ".id", "identifier");
            if (!ids.add(id)) throw invalid(item + ".id", "duplicate");
            String type = text(column, "type", item + ".type");
            if (!LINE_TYPES.contains(type)) throw invalid(item + ".type", "unsupported");
            boolean textual = Set.of("text", "textarea").contains(type);
            boolean number = "number".equals(type);
            if (!textual && column.has("maxLength")) throw invalid(item + ".maxLength", "unsupported");
            if (!number && column.has("scale")) throw invalid(item + ".scale", "unsupported");
            MdSimpleFormKinds.Parts parts = MdSimpleFormKinds.parts(column, type, item);
            result.add(new LineField(
                    id,
                    type,
                    labels(column, "labels", item + ".labels"),
                    bool(column, "required", item + ".required"),
                    textual ? integer(column, "maxLength", item + ".maxLength", 1, 4000) : null,
                    number ? integer(column, "scale", item + ".scale", 0, 6) : null,
                    parts.options(),
                    parts.target(),
                    MdSimpleFormLogic.formula(column, "formula", item + ".formula")));
        }
        return List.copyOf(result);
    }

    /**
     * The number of the form's records, or null when it names none: a pattern of 3 to 60 characters with exactly one
     * counter token, braces only in tokens and no control character; a yearly counter needs a year in the pattern, a
     * monthly one the year and the month — a number never repeats within a form.
     */
    static @Nullable Numbering numbering(JsonNode entity) {
        if (!entity.has("numbering")) return null;
        String at = "entity.numbering";
        JsonNode numbering = required(entity, "numbering", at);
        known(numbering, Set.of("pattern", "reset", "assignOn", "labels"), at);
        String pattern = text(numbering, "pattern", at + ".pattern");
        String reset = text(numbering, "reset", at + ".reset");
        if (!RESETS.contains(reset)) throw invalid(at + ".reset", "unsupported");
        if (!fits(pattern, reset)) throw invalid(at + ".pattern", "numbering");
        String assignOn = numbering.has("assignOn") ? text(numbering, "assignOn", at + ".assignOn") : null;
        Labels names = numbering.has("labels") ? labels(numbering, "labels", at + ".labels") : null;
        return new Numbering(pattern, reset, assignOn, names);
    }

    /** Whether a pattern gives numbers that never repeat in the reset period it names. */
    static boolean fits(String pattern, String reset) {
        int length = pattern.codePointCount(0, pattern.length());
        if (length < 3
                || length > 60
                || pattern.codePoints()
                        .anyMatch(point -> Character.isISOControl(point)
                                || Character.getType(point) == Character.FORMAT
                                || (point >= 0xd800 && point <= 0xdfff))) {
            return false;
        }
        Matcher tokens = TOKEN.matcher(pattern);
        Set<String> named = new HashSet<>();
        int counters = 0;
        while (tokens.find()) {
            String token = tokens.group(1);
            if (token.startsWith("N")) counters++;
            else named.add(token);
        }
        String rest = TOKEN.matcher(pattern).replaceAll("");
        if (counters != 1 || rest.indexOf('{') >= 0 || rest.indexOf('}') >= 0) return false;
        boolean year = named.contains("YYYY") || named.contains("YY");
        return switch (reset) {
            case "year" -> year;
            case "month" -> year && named.contains("MM");
            default -> true;
        };
    }
}
