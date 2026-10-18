package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A measure an administrator computes by a formula of the formula language (ADR-0042) over the aggregates of a
 * dataset's fields (ADR-0044): {@code count}, {@code sum_<field>}, {@code avg_<field>}, {@code min_<field>},
 * {@code max_<field>} of a number or of money, {@code distinct_<field>} of a field whose different values are counted.
 * The aggregates come from the report's statement under the reader's scope; the formula only combines them — a ratio of
 * totals is the ratio of exact totals — and a division by zero gives an empty value.
 *
 * @param key     the measure's key, named by a report as {@code {"op":"formula","field":"<key>"}}
 * @param labels  its names in ru, uz and en
 * @param formula its text
 */
public record QueryComputedMeasure(String key, Map<String, String> labels, String formula) {
    public static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9]{0,63}");
    public static final String COUNT = "count";

    public QueryComputedMeasure {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(formula, "formula");
        labels = Map.copyOf(labels);
    }

    /** The aggregates a formula may read over these fields, by name, each a number. */
    public static Map<String, FormulaType> names(List<QueryField> fields) {
        Map<String, FormulaType> names = new LinkedHashMap<>();
        names.put(COUNT, FormulaType.NUMBER);
        for (QueryField field : fields) {
            if (QueryAggregates.measurable(field)) {
                for (String op : List.of("sum", "avg", "min", "max"))
                    names.put(op + "_" + field.key(), FormulaType.NUMBER);
            }
            if (QueryAggregates.countable(field)) names.put("distinct_" + field.key(), FormulaType.NUMBER);
        }
        return names;
    }

    /** The checked formula over the aggregates of {@code fields}; a {@code FormulaException} names its problem. */
    public Formula compile(List<QueryField> fields) {
        return Formula.compile(formula, names(fields));
    }
}
