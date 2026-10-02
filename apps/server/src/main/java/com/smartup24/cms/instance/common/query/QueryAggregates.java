package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregate.Group;
import com.smartup24.cms.instance.common.query.QueryAggregate.Measure;
import com.smartup24.cms.instance.common.query.QueryAggregate.Op;
import com.smartup24.cms.instance.common.query.QueryAggregate.Trunc;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and validates a report over a list (ADR-0032, 10.2; plan 10/10, item 5.8) against the registry, as
 * {@link QueryCompiler} does a page:
 * <pre>groupBy  [{"field":"status"},{"field":"createdAt","trunc":"month"}]
 * measures [{"op":"count"},{"op":"sum","field":"total"}]</pre>
 * A report groups by a choice, an enumeration, a yes/no, a reference or a date bucket ({@code day}, {@code week},
 * {@code month}, {@code quarter}, {@code year}; a date without one is bucketed by month) and answers {@code count} or
 * {@code sum}/{@code avg}/{@code min}/{@code max} of a number or of an amount of money — money is grouped by its
 * currency as well, so different currencies are never added up. Only the fields the viewer may see take part (a
 * restricted field answers as an unknown one, ADR-0016), and the filter is the list's own. Any error is a 422 whose
 * fields point at the part to fix: {@code groupBy[1].trunc}, {@code measures[0].field}, {@code filter[2].op}.
 */
public final class QueryAggregates {

    public static final String REPORT_INVALID = "QUERY_REPORT_INVALID";

    /** A chart or a table reads two groupings; a third makes neither readable. */
    public static final int MAX_GROUPS = 2;

    public static final int MAX_MEASURES = 4;

    /** The format of a money list field, whose value is the amount (ADR-0032, 4.1). */
    public static final String MONEY_FORMAT = "money";

    /** The format of the hidden list field that holds the currency of a money field. */
    public static final String CURRENCY_FORMAT = "currency";

    /** The key suffix of that currency field: {@code total} has {@code totalCurrency}. */
    public static final String CURRENCY_SUFFIX = "Currency";

    private static final Set<String> GROUP_KEYS = Set.of("field", "trunc");
    private static final Set<String> MEASURE_KEYS = Set.of("op", "field");

    /** A static compiler reads the client's JSON text with the shared default mapper (plan 10/10, item 3.11). */
    private static final JsonMapper JSON = JsonMapper.shared();

    private static final Logger log = LoggerFactory.getLogger(QueryAggregates.class);

    private QueryAggregates() {}

    /** A report given as request parameters: {@code groupBy} and {@code measures} as JSON arrays, the filter DSL. */
    public static QueryAggregate compile(
            QueryList list, @Nullable String groupBy, @Nullable String measures, @Nullable String filter) {
        List<FieldErrorItem> errors = new ArrayList<>();
        JsonNode groups = readArray(groupBy, "groupBy", errors);
        JsonNode values = readArray(measures, "measures", errors);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return compile(list, groups, values, filter);
    }

    /** A report as a saved view holds it: {@code groupBy} and {@code measures} as JSON trees, the filter DSL. */
    public static QueryAggregate compile(
            QueryList list, @Nullable JsonNode groupBy, @Nullable JsonNode measures, @Nullable String filter) {
        List<FieldErrorItem> errors = new ArrayList<>();
        List<Group> groups = groups(list, groupBy, errors);
        List<Measure> parsed = measures(list, measures, errors);
        QueryPlan plan = null;
        try {
            plan = QueryCompiler.compile(list, filter, null, null, null);
        } catch (ApiException e) {
            errors.addAll(e.getFieldErrors());
        }
        if (!errors.isEmpty() || plan == null) {
            throw invalid(errors);
        }
        List<Group> all = new ArrayList<>(groups);
        for (Measure measure : parsed) {
            currencyGroup(list, measure)
                    .filter(currency -> all.stream()
                            .noneMatch(group ->
                                    group.field().key().equals(currency.field().key())))
                    .ifPresent(all::add);
        }
        return new QueryAggregate(list, all, parsed, plan);
    }

    private static List<Group> groups(QueryList list, @Nullable JsonNode node, List<FieldErrorItem> errors) {
        List<Group> groups = new ArrayList<>();
        if (node == null || node.isNull()) return groups;
        if (!node.isArray()) {
            errors.add(FieldErrorItem.keyed("groupBy", REPORT_INVALID, "error.common.field_report_not_array"));
            return groups;
        }
        if (node.size() > MAX_GROUPS) {
            errors.add(FieldErrorItem.keyed(
                    "groupBy", REPORT_INVALID, "error.common.field_report_groups_too_many", Map.of("max", MAX_GROUPS)));
            return groups;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String at = "groupBy[" + i + "]";
            JsonNode item = node.get(i);
            if (!item.isObject()) {
                errors.add(FieldErrorItem.keyed(at, REPORT_INVALID, "error.common.field_report_item_invalid"));
                continue;
            }
            if (!knownKeys(item, GROUP_KEYS, at, errors)) continue;
            Optional<Group> group = group(list, item, at, errors);
            if (group.isEmpty()) continue;
            if (!seen.add(group.get().field().key())) {
                errors.add(FieldErrorItem.keyed(at + ".field", REPORT_INVALID, "error.common.field_report_repeated"));
                continue;
            }
            groups.add(group.get());
        }
        return groups;
    }

    private static Optional<Group> group(QueryList list, JsonNode item, String at, List<FieldErrorItem> errors) {
        Optional<QueryField> found = viewerField(list, item, at, errors);
        if (found.isEmpty()) return Optional.empty();
        QueryField field = found.get();
        if (!groupable(field)) {
            errors.add(FieldErrorItem.keyed(
                    at + ".field",
                    REPORT_INVALID,
                    "error.common.field_report_group_invalid",
                    Map.of("field", field.key())));
            return Optional.empty();
        }
        boolean dated = field.type() == QueryFieldType.DATE || field.type() == QueryFieldType.INSTANT;
        JsonNode truncNode = item.get("trunc");
        if (truncNode == null || truncNode.isNull()) {
            return Optional.of(new Group(field, dated ? Trunc.MONTH : null, false));
        }
        Optional<Trunc> trunc = truncNode.isString() ? Trunc.fromWire(truncNode.asString()) : Optional.empty();
        if (!dated || trunc.isEmpty()) {
            errors.add(FieldErrorItem.keyed(at + ".trunc", REPORT_INVALID, "error.common.field_report_trunc_invalid"));
            return Optional.empty();
        }
        return Optional.of(new Group(field, trunc.get(), false));
    }

    private static List<Measure> measures(QueryList list, @Nullable JsonNode node, List<FieldErrorItem> errors) {
        List<Measure> measures = new ArrayList<>();
        if (node == null || node.isNull() || (node.isArray() && node.isEmpty())) {
            measures.add(new Measure(Op.COUNT, null));
            return measures;
        }
        if (!node.isArray()) {
            errors.add(FieldErrorItem.keyed("measures", REPORT_INVALID, "error.common.field_report_not_array"));
            return measures;
        }
        if (node.size() > MAX_MEASURES) {
            errors.add(FieldErrorItem.keyed(
                    "measures",
                    REPORT_INVALID,
                    "error.common.field_report_measures_too_many",
                    Map.of("max", MAX_MEASURES)));
            return measures;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String at = "measures[" + i + "]";
            JsonNode item = node.get(i);
            if (!item.isObject()) {
                errors.add(FieldErrorItem.keyed(at, REPORT_INVALID, "error.common.field_report_item_invalid"));
                continue;
            }
            if (!knownKeys(item, MEASURE_KEYS, at, errors)) continue;
            Optional<Measure> measure = measure(list, item, at, errors);
            if (measure.isEmpty()) continue;
            if (!seen.add(measure.get().op().wire() + ":" + measure.get().fieldKey())) {
                errors.add(FieldErrorItem.keyed(at, REPORT_INVALID, "error.common.field_report_repeated"));
                continue;
            }
            measures.add(measure.get());
        }
        return measures;
    }

    private static Optional<Measure> measure(QueryList list, JsonNode item, String at, List<FieldErrorItem> errors) {
        JsonNode opNode = item.get("op");
        Optional<Op> op = opNode != null && opNode.isString() ? Op.fromWire(opNode.asString()) : Optional.empty();
        if (op.isEmpty()) {
            errors.add(FieldErrorItem.keyed(at + ".op", REPORT_INVALID, "error.common.field_report_op_invalid"));
            return Optional.empty();
        }
        JsonNode fieldNode = item.get("field");
        boolean hasField = fieldNode != null && !fieldNode.isNull();
        if (op.get() == Op.COUNT) {
            if (hasField) {
                errors.add(
                        FieldErrorItem.keyed(at + ".field", REPORT_INVALID, "error.common.field_report_count_field"));
                return Optional.empty();
            }
            return Optional.of(new Measure(Op.COUNT, null));
        }
        Optional<QueryField> found = viewerField(list, item, at, errors);
        if (found.isEmpty()) return Optional.empty();
        if (!measurable(found.get())) {
            errors.add(FieldErrorItem.keyed(
                    at + ".field",
                    REPORT_INVALID,
                    "error.common.field_report_measure_invalid",
                    Map.of("field", found.get().key())));
            return Optional.empty();
        }
        return Optional.of(new Measure(op.get(), found.get()));
    }

    /** The field named by {@code item.field} if the viewer can see it; a hidden field answers as a missing one. */
    private static Optional<QueryField> viewerField(
            QueryList list, JsonNode item, String at, List<FieldErrorItem> errors) {
        JsonNode keyNode = item.get("field");
        String key = keyNode != null && keyNode.isString() ? keyNode.asString() : "";
        Optional<QueryField> found = list.viewerField(key);
        if (found.isEmpty()) {
            errors.add(FieldErrorItem.keyed(
                    at + ".field",
                    QueryCompiler.UNKNOWN_FIELD,
                    "error.common.field_report_field_unknown",
                    Map.of("field", key)));
        }
        return found;
    }

    /** A field whose values make few groups: a choice, a yes/no, a reference or a date bucket. */
    static boolean groupable(QueryField field) {
        return switch (field.type()) {
            case ENUM, BOOLEAN, DATE, INSTANT -> true;
            case NUMBER -> field.ref() != null;
            case TEXT, TIME, REF_SET, OBJECT -> false;
        };
    }

    /** A number or the amount of money; a reference holds a row key, which adds up to nothing. */
    static boolean measurable(QueryField field) {
        return field.type() == QueryFieldType.NUMBER
                && field.ref() == null
                && (field.format() == null || MONEY_FORMAT.equals(field.format()));
    }

    /** The currency group a money measure brings, when the list has the money's currency field. */
    private static Optional<Group> currencyGroup(QueryList list, Measure measure) {
        QueryField field = measure.field();
        if (field == null || !MONEY_FORMAT.equals(field.format())) return Optional.empty();
        return list.field(field.key() + CURRENCY_SUFFIX)
                .filter(currency -> CURRENCY_FORMAT.equals(currency.format()))
                .map(currency -> new Group(currency, null, true));
    }

    private static boolean knownKeys(JsonNode item, Set<String> keys, String at, List<FieldErrorItem> errors) {
        boolean known = true;
        for (Map.Entry<String, JsonNode> entry : item.properties()) {
            if (!keys.contains(entry.getKey())) {
                errors.add(FieldErrorItem.keyed(at + "." + entry.getKey(), REPORT_INVALID, "error.field.unknown_key"));
                known = false;
            }
        }
        return known;
    }

    private static @Nullable JsonNode readArray(@Nullable String text, String at, List<FieldErrorItem> errors) {
        if (text == null || text.isBlank()) return null;
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            log.debug("Report part {} is not JSON: {}", at, e.getOriginalMessage());
            errors.add(FieldErrorItem.keyed(at, REPORT_INVALID, "error.common.field_report_not_json"));
            return null;
        }
    }

    private static ApiException invalid(List<FieldErrorItem> errors) {
        return ApiException.validation("error.common.report_invalid", errors);
    }
}
