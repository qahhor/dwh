package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.query.QueryAggregate.By;
import com.smartup24.cms.instance.common.query.QueryAggregate.Order;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Parses the order of a report's groups and the top it keeps (plan 10/10, the form and report builder):
 * <pre>sort  {"by":"measure","measure":0,"direction":"desc"}   by group | label | measure
 * limit 10</pre>
 * Both need a grouping; a measure is named by its position. Errors point at {@code sort.by}, {@code sort.measure},
 * {@code sort.direction} and {@code limit}.
 */
final class QueryAggregateOrders {
    private static final Set<String> KEYS = Set.of("by", "measure", "direction");

    private QueryAggregateOrders() {}

    /** The order the report asks for; the default order when it names none, and any problem in {@code errors}. */
    static Order order(
            @Nullable JsonNode sort, @Nullable JsonNode limit, int groups, int measures, List<FieldErrorItem> errors) {
        boolean sorted = sort != null && !sort.isNull();
        boolean limited = limit != null && !limit.isNull();
        if (!sorted && !limited) return Order.DEFAULT;
        if (groups == 0) {
            errors.add(FieldErrorItem.keyed(
                    sorted ? "sort" : "limit",
                    QueryAggregates.REPORT_INVALID,
                    "error.common.field_report_order_ungrouped"));
            return Order.DEFAULT;
        }
        Integer top = limit != null && limited ? limit(limit, errors) : null;
        if (sort == null || !sorted) return new Order(By.GROUP, 0, false, top);
        if (!sort.isObject()) {
            errors.add(invalid("sort"));
            return Order.DEFAULT;
        }
        for (Map.Entry<String, JsonNode> entry : sort.properties()) {
            if (!KEYS.contains(entry.getKey())) {
                errors.add(FieldErrorItem.keyed(
                        "sort." + entry.getKey(), QueryAggregates.REPORT_INVALID, "error.field.unknown_key"));
                return Order.DEFAULT;
            }
        }
        JsonNode byNode = sort.get("by");
        Optional<By> by = byNode != null && byNode.isString() ? By.fromWire(byNode.asString()) : Optional.empty();
        if (by.isEmpty()) {
            errors.add(invalid("sort.by"));
            return Order.DEFAULT;
        }
        Boolean descending = direction(sort.get("direction"), errors);
        int measure = measure(sort.get("measure"), by.get(), measures, errors);
        if (descending == null || measure < 0) return Order.DEFAULT;
        return new Order(by.get(), measure, descending, top);
    }

    private static @Nullable Integer limit(JsonNode limit, List<FieldErrorItem> errors) {
        if (!limit.isIntegralNumber()
                || !limit.canConvertToInt()
                || limit.intValue() < 1
                || limit.intValue() > QueryAggregate.MAX_ROWS) {
            errors.add(FieldErrorItem.keyed(
                    "limit",
                    QueryAggregates.REPORT_INVALID,
                    "error.common.field_report_limit_invalid",
                    Map.of("max", QueryAggregate.MAX_ROWS)));
            return null;
        }
        return limit.intValue();
    }

    /** True for {@code desc}, false for {@code asc} or none; null after a problem. */
    private static @Nullable Boolean direction(@Nullable JsonNode node, List<FieldErrorItem> errors) {
        if (node == null || node.isNull()) return Boolean.FALSE;
        if (node.isString() && "asc".equals(node.asString())) return Boolean.FALSE;
        if (node.isString() && "desc".equals(node.asString())) return Boolean.TRUE;
        errors.add(invalid("sort.direction"));
        return null;
    }

    /** The position of the measure ordered by, 0 for another order; -1 after a problem. */
    private static int measure(@Nullable JsonNode node, By by, int measures, List<FieldErrorItem> errors) {
        boolean given = node != null && !node.isNull();
        if (by != By.MEASURE) {
            if (!given) return 0;
            errors.add(invalid("sort.measure"));
            return -1;
        }
        if (node == null || !given || !node.isIntegralNumber() || node.intValue() < 0 || node.intValue() >= measures) {
            errors.add(invalid("sort.measure"));
            return -1;
        }
        return node.intValue();
    }

    private static FieldErrorItem invalid(String at) {
        return FieldErrorItem.keyed(at, QueryAggregates.REPORT_INVALID, "error.common.field_report_sort_invalid");
    }
}
