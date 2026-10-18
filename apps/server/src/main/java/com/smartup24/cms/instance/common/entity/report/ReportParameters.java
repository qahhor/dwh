package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregateSpec.Anchor;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The parameters of a saved report (ADR-0044): what its reader fills when opening it, each bound to a filter of one
 * field of the report's list — a period of a date or a moment, the values of a choice or a list, the rows of a reference
 * (a record, a user, an org unit) or yes/no.
 *
 * <pre>[{"key": "period", "field": "date", "required": true, "default": {"preset": "this_month"}},
 *  {"key": "region", "field": "region", "labels": {"ru": "Region", "uz": "Hudud", "en": "Region"}}]</pre>
 *
 * A value is a typed part of the filter DSL the list's own compiler checks and binds as SQL parameters; nothing a reader
 * sends becomes SQL text. A period counts in the reader's time zone: a date between its first and last day, a moment
 * from the start of the first day to the start of the day after the last. The reader's scope and field rights stay the
 * list's: a parameter only narrows the report.
 */
public final class ReportParameters {

    public static final int MAX_PARAMETERS = 8;
    public static final String INVALID = "REPORT_PARAMETER_INVALID";
    public static final Set<String> PRESETS = Set.of(
            "today",
            "this_week",
            "this_month",
            "last_month",
            "this_quarter",
            "last_quarter",
            "this_year",
            "ytd",
            "last_7_days",
            "last_30_days");

    private static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9]{0,31}");
    private static final Set<String> KEYS = Set.of("key", "field", "required", "default", "labels");
    private static final Set<String> LANGUAGES = Set.of("ru", "uz", "en");
    private static final int MAX_VALUES = QueryCompiler.MAX_IN_VALUES;
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final Logger log = LoggerFactory.getLogger(ReportParameters.class);

    private ReportParameters() {}

    /**
     * The declared parameters in their canonical form, or null when there are none; problems at
     * {@code parameters[i].<part>} go to {@code errors}.
     */
    public static @Nullable ArrayNode canonical(
            QueryList list, @Nullable JsonNode declared, Anchor anchor, List<FieldErrorItem> errors) {
        if (declared == null || declared.isNull() || (declared.isArray() && declared.isEmpty())) return null;
        if (!declared.isArray() || declared.size() > MAX_PARAMETERS) {
            errors.add(invalid("parameters", "error.common.report_parameters_invalid"));
            return null;
        }
        ArrayNode canonical = JSON.createArrayNode();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < declared.size(); i++) {
            String at = "parameters[" + i + "]";
            JsonNode item = declared.get(i);
            if (!item.isObject() || !KEYS.containsAll(item.propertyNames())) {
                errors.add(invalid(at, "error.common.report_parameters_invalid"));
                continue;
            }
            String key = text(item.get("key"));
            if (key == null || !KEY.matcher(key).matches() || !keys.add(key)) {
                errors.add(invalid(at + ".key", "error.common.report_parameter_key"));
                continue;
            }
            Optional<QueryField> field = field(list, text(item.get("field")));
            if (field.isEmpty()) {
                errors.add(invalid(at + ".field", "error.common.report_parameter_field"));
                continue;
            }
            JsonNode required = item.get("required");
            if (required != null && !required.isBoolean()) {
                errors.add(invalid(at + ".required", "error.common.report_parameters_invalid"));
                continue;
            }
            ObjectNode out = canonical
                    .addObject()
                    .put("key", key)
                    .put("field", field.get().key());
            out.put("required", required != null && required.booleanValue());
            labels(item.get("labels"), at, out, errors);
            JsonNode value = item.get("default");
            if (value != null && !value.isNull()) {
                int before = errors.size();
                conditions(list, field.get(), value, at + ".default", anchor, errors);
                if (errors.size() == before) out.set("default", value);
            }
        }
        return canonical;
    }

    /**
     * The filter the reader's values add to the report, as filter DSL text, or null when they add nothing: each value
     * given, else its default; a required parameter without either is refused.
     *
     * @throws ApiException 422 {@code error.common.report_parameters_invalid} naming {@code parameters.<key>}
     */
    public static @Nullable String filter(
            QueryList list, @Nullable JsonNode declared, @Nullable JsonNode values, Anchor anchor) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (values != null && !values.isNull() && !values.isObject()) {
            throw ApiException.validation(
                    "error.common.report_parameters_invalid",
                    List.of(invalid("parameters", "error.common.report_parameters_invalid")));
        }
        Set<String> known = new HashSet<>();
        ArrayNode filter = JSON.createArrayNode();
        if (declared != null && declared.isArray()) {
            for (JsonNode item : declared) {
                String key = text(item.get("key"));
                if (key == null) continue;
                known.add(key);
                JsonNode given = values == null ? null : values.get(key);
                JsonNode value = given == null || given.isNull() ? item.get("default") : given;
                String at = "parameters." + key;
                if (value == null || value.isNull()) {
                    JsonNode required = item.get("required");
                    if (required != null && required.booleanValue()) {
                        errors.add(invalid(at, "error.common.report_parameter_required"));
                    }
                    continue;
                }
                Optional<QueryField> field = field(list, text(item.get("field")));
                if (field.isEmpty()) {
                    errors.add(invalid(at, "error.common.report_parameter_field"));
                    continue;
                }
                filter.addAll(conditions(list, field.get(), value, at, anchor, errors));
            }
        }
        if (values != null && values.isObject()) {
            for (String key : values.propertyNames()) {
                if (!known.contains(key)) errors.add(invalid("parameters." + key, "error.field.unknown_key"));
            }
        }
        if (!errors.isEmpty()) throw ApiException.validation("error.common.report_parameters_invalid", errors);
        return filter.isEmpty() ? null : filter.toString();
    }

    /** The keys of the declared parameters bound to {@code fieldKey}. */
    public static List<String> boundTo(@Nullable JsonNode declared, String fieldKey) {
        List<String> keys = new ArrayList<>();
        if (declared == null || !declared.isArray()) return keys;
        for (JsonNode item : declared) {
            if (fieldKey.equals(text(item.get("field")))) keys.add(String.valueOf(text(item.get("key"))));
        }
        return keys;
    }

    /** A field a parameter may narrow: visible to the reader, filterable, a date, a choice, a reference or yes/no. */
    private static Optional<QueryField> field(QueryList list, @Nullable String key) {
        if (key == null) return Optional.empty();
        return list.viewerField(key).filter(QueryField::filterable).filter(field -> switch (field.type()) {
            case DATE, INSTANT, ENUM, BOOLEAN -> true;
            case NUMBER -> field.ref() != null;
            default -> false;
        });
    }

    /** The conditions of one value, checked by the list's compiler; problems at {@code at}. */
    private static ArrayNode conditions(
            QueryList list, QueryField field, JsonNode value, String at, Anchor anchor, List<FieldErrorItem> errors) {
        ArrayNode conditions = JSON.createArrayNode();
        switch (field.type()) {
            case DATE, INSTANT -> {
                LocalDate[] period = period(value, anchor);
                if (period == null) {
                    errors.add(invalid(at, "error.common.report_parameter_period"));
                    return conditions;
                }
                if (field.type() == QueryFieldType.DATE) {
                    conditions
                            .addObject()
                            .put("field", field.key())
                            .put("op", "between")
                            .putArray("value")
                            .add(period[0].toString())
                            .add(period[1].toString());
                } else {
                    conditions
                            .addObject()
                            .put("field", field.key())
                            .put("op", "gte")
                            .put(
                                    "value",
                                    period[0]
                                            .atStartOfDay(anchor.zone())
                                            .toInstant()
                                            .toString());
                    conditions
                            .addObject()
                            .put("field", field.key())
                            .put("op", "lt")
                            .put(
                                    "value",
                                    period[1]
                                            .plusDays(1)
                                            .atStartOfDay(anchor.zone())
                                            .toInstant()
                                            .toString());
                }
            }
            default -> {
                boolean many = value.isArray();
                if ((many && (value.isEmpty() || value.size() > MAX_VALUES)) || (!many && !value.isValueNode())) {
                    errors.add(invalid(at, "error.common.report_parameter_value"));
                    return conditions;
                }
                ObjectNode condition =
                        conditions.addObject().put("field", field.key()).put("op", many ? "in" : "eq");
                condition.set("value", value);
            }
        }
        try {
            QueryCompiler.compile(list, conditions.toString(), null, null, null);
        } catch (ApiException failure) {
            log.debug("Report parameter {} does not fit its field: {}", at, failure.getMessageKey());
            errors.add(invalid(at, "error.common.report_parameter_value"));
        }
        return conditions;
    }

    /**
     * The first and last day of a period: {@code {"from": "2026-10-01", "to": "2026-10-31"}} or
     * {@code {"preset": "this_month"}} counted from today in the reader's zone; null when it is neither.
     */
    static LocalDate @Nullable [] period(JsonNode value, Anchor anchor) {
        if (!value.isObject()) return null;
        String preset = text(value.get("preset"));
        if (preset != null)
            return value.size() == 1 && PRESETS.contains(preset) ? preset(preset, anchor.today()) : null;
        if (value.size() != 2) return null;
        try {
            String from = text(value.get("from"));
            String to = text(value.get("to"));
            if (from == null || to == null) return null;
            LocalDate first = LocalDate.parse(from);
            LocalDate last = LocalDate.parse(to);
            boolean fits = !last.isBefore(first) && first.getYear() >= 1 && last.getYear() < 9999;
            return fits ? new LocalDate[] {first, last} : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate[] preset(String preset, LocalDate today) {
        LocalDate month = today.withDayOfMonth(1);
        LocalDate quarter = LocalDate.of(today.getYear(), (today.getMonthValue() - 1) / 3 * 3 + 1, 1);
        return switch (preset) {
            case "today" -> new LocalDate[] {today, today};
            case "this_week" ->
                new LocalDate[] {today.minusDays(today.getDayOfWeek().getValue() - 1L), today};
            case "this_month" -> new LocalDate[] {month, month.plusMonths(1).minusDays(1)};
            case "last_month" -> new LocalDate[] {month.minusMonths(1), month.minusDays(1)};
            case "this_quarter" ->
                new LocalDate[] {quarter, quarter.plusMonths(3).minusDays(1)};
            case "last_quarter" -> new LocalDate[] {quarter.minusMonths(3), quarter.minusDays(1)};
            case "this_year" ->
                new LocalDate[] {
                    today.withDayOfYear(1), today.withDayOfYear(1).plusYears(1).minusDays(1)
                };
            case "ytd" -> new LocalDate[] {today.withDayOfYear(1), today};
            case "last_7_days" -> new LocalDate[] {today.minusDays(6), today};
            default -> new LocalDate[] {today.minusDays(29), today};
        };
    }

    private static void labels(@Nullable JsonNode labels, String at, ObjectNode out, List<FieldErrorItem> errors) {
        if (labels == null || labels.isNull()) return;
        boolean fits = labels.isObject() && labels.size() == LANGUAGES.size();
        if (fits) {
            for (String language : LANGUAGES) {
                String text = text(labels.get(language));
                fits &= text != null && !text.isBlank() && text.strip().length() <= 80;
            }
        }
        if (!fits) {
            errors.add(invalid(at + ".labels", "error.common.report_parameter_labels"));
            return;
        }
        ObjectNode copy = out.putObject("labels");
        for (String language : List.of("ru", "uz", "en")) {
            copy.put(language, String.valueOf(text(labels.get(language))).strip());
        }
    }

    private static @Nullable String text(@Nullable JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static FieldErrorItem invalid(String at, String key) {
        return FieldErrorItem.keyed(at, INVALID, key);
    }
}
