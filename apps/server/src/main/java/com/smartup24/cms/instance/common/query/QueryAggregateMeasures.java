package com.smartup24.cms.instance.common.query;

import static com.smartup24.cms.instance.common.query.QueryAggregates.REPORT_INVALID;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaException;
import com.smartup24.cms.instance.common.query.QueryAggregate.Computation;
import com.smartup24.cms.instance.common.query.QueryAggregate.Measure;
import com.smartup24.cms.instance.common.query.QueryAggregate.Op;
import com.smartup24.cms.instance.common.query.QueryAggregate.Share;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Parses the measures of a report (ADR-0032, 10.2; ADR-0044): {@code count}, {@code count_distinct} of a field, a sum,
 * average, minimum or maximum of a number or of money — a count or a sum may show its share of a total — and a measure
 * of the dataset computed by a formula over aggregates ({@code {"op":"formula","field":"avgCheck"}}), whose aggregates
 * the statement reads under the reader's field rights. Errors point at {@code measures[i].op}, {@code .field},
 * {@code .share}.
 */
final class QueryAggregateMeasures {
    private static final Set<String> KEYS = Set.of("op", "field", "share");

    private QueryAggregateMeasures() {}

    static List<Measure> parse(
            QueryList list, @Nullable JsonNode node, List<QueryComputedMeasure> computed, List<FieldErrorItem> errors) {
        List<Measure> measures = new ArrayList<>();
        if (node == null || node.isNull() || (node.isArray() && node.isEmpty())) {
            measures.add(new Measure(Op.COUNT, null));
            return measures;
        }
        if (!node.isArray()) {
            errors.add(FieldErrorItem.keyed("measures", REPORT_INVALID, "error.common.field_report_not_array"));
            return measures;
        }
        if (node.size() > QueryAggregates.MAX_MEASURES) {
            errors.add(FieldErrorItem.keyed(
                    "measures",
                    REPORT_INVALID,
                    "error.common.field_report_measures_too_many",
                    Map.of("max", QueryAggregates.MAX_MEASURES)));
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
            if (!QueryAggregates.knownKeys(item, KEYS, at, errors)) continue;
            Optional<Measure> measure = measure(list, item, at, computed, errors);
            if (measure.isEmpty()) continue;
            Share share = measure.get().share();
            if (!seen.add(measure.get().op().wire() + ":" + measure.get().fieldKey() + ":" + share)) {
                errors.add(FieldErrorItem.keyed(at, REPORT_INVALID, "error.common.field_report_repeated"));
                continue;
            }
            measures.add(measure.get());
        }
        return measures;
    }

    private static Optional<Measure> measure(
            QueryList list,
            JsonNode item,
            String at,
            List<QueryComputedMeasure> computed,
            List<FieldErrorItem> errors) {
        JsonNode opNode = item.get("op");
        Optional<Op> op = opNode != null && opNode.isString() ? Op.fromWire(opNode.asString()) : Optional.empty();
        if (op.isEmpty()) {
            errors.add(FieldErrorItem.keyed(at + ".op", REPORT_INVALID, "error.common.field_report_op_invalid"));
            return Optional.empty();
        }
        int problems = errors.size();
        Share share = share(item, op.get(), at, errors);
        if (errors.size() > problems) return Optional.empty();
        if (op.get() == Op.FORMULA) return formula(list, item, at, computed, errors);
        JsonNode fieldNode = item.get("field");
        boolean hasField = fieldNode != null && !fieldNode.isNull();
        if (op.get() == Op.COUNT) {
            if (hasField) {
                errors.add(
                        FieldErrorItem.keyed(at + ".field", REPORT_INVALID, "error.common.field_report_count_field"));
                return Optional.empty();
            }
            return Optional.of(new Measure(Op.COUNT, null, share));
        }
        Optional<QueryField> found = QueryAggregates.viewerField(list, item, at, errors);
        if (found.isEmpty()) return Optional.empty();
        boolean fits = op.get() == Op.COUNT_DISTINCT
                ? QueryAggregates.countable(found.get())
                : QueryAggregates.measurable(found.get());
        if (!fits) {
            errors.add(FieldErrorItem.keyed(
                    at + ".field",
                    REPORT_INVALID,
                    op.get() == Op.COUNT_DISTINCT
                            ? "error.common.field_report_distinct_invalid"
                            : "error.common.field_report_measure_invalid",
                    Map.of("field", found.get().key())));
            return Optional.empty();
        }
        return Optional.of(new Measure(op.get(), found.get(), share));
    }

    /**
     * A measure of the dataset computed by a formula: its aggregates — those the formula reads — read over the fields
     * the reader may see; a formula over a field the reader may not see is no measure of theirs.
     */
    private static Optional<Measure> formula(
            QueryList list,
            JsonNode item,
            String at,
            List<QueryComputedMeasure> computed,
            List<FieldErrorItem> errors) {
        JsonNode keyNode = item.get("field");
        String key = keyNode != null && keyNode.isString() ? keyNode.asString() : "";
        Optional<QueryComputedMeasure> declared =
                computed.stream().filter(measure -> measure.key().equals(key)).findFirst();
        Formula formula = null;
        try {
            formula = declared.isEmpty() ? null : declared.get().compile(list.viewerFields());
        } catch (FormulaException unreadable) {
            formula = null;
        }
        if (formula == null) {
            errors.add(FieldErrorItem.keyed(
                    at + ".field", REPORT_INVALID, "error.common.field_report_formula_unknown", Map.of("field", key)));
            return Optional.empty();
        }
        List<String> names = List.copyOf(formula.fields().keySet());
        List<Measure> bases = new ArrayList<>();
        for (String name : names) bases.add(base(list, name));
        return Optional.of(new Measure(Op.FORMULA, null, null, new Computation(key, formula, bases, names)));
    }

    /** The aggregate a formula reads by its name: {@code count}, {@code sum_total}, {@code distinct_client}. */
    private static Measure base(QueryList list, String name) {
        if (QueryComputedMeasure.COUNT.equals(name)) return new Measure(Op.COUNT, null);
        int split = name.indexOf('_');
        String prefix = name.substring(0, split);
        QueryField field = list.viewerField(name.substring(split + 1)).orElseThrow();
        Op op = "distinct".equals(prefix)
                ? Op.COUNT_DISTINCT
                : Op.fromWire(prefix).orElseThrow();
        return new Measure(op, field);
    }

    /**
     * The share a measure asks for, null without one; a problem goes to {@code errors} — only a count or a sum is a part
     * of a total (ADR-0044).
     */
    private static @Nullable Share share(JsonNode item, Op op, String at, List<FieldErrorItem> errors) {
        JsonNode node = item.get("share");
        if (node == null || node.isNull()) return null;
        Optional<Share> share = node.isString() ? Share.fromWire(node.asString()) : Optional.empty();
        if (share.isEmpty() || (op != Op.COUNT && op != Op.SUM)) {
            errors.add(FieldErrorItem.keyed(at + ".share", REPORT_INVALID, "error.common.field_report_share_invalid"));
            return null;
        }
        return share.get();
    }
}
