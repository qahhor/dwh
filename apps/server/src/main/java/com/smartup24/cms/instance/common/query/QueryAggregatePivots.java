package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregate.By;
import com.smartup24.cms.instance.common.query.QueryAggregate.Group;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateRow;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateTotal;
import com.smartup24.cms.instance.common.web.RequestLanguage;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Lays out a pivot (ADR-0044) from the cells and totals of its statement, once their words are read: at most
 * {@value QueryAggregateShape#MAX_COLUMNS} columns and {@value QueryAggregateShape#MAX_CELLS} filled cells — a larger
 * pivot is refused with a request to narrow it, never shown in part — and the rows in the report's order (by the row's
 * value, its words or its total of a measure), cut to the top asked for or to {@value QueryAggregate#MAX_ROWS}. The
 * columns follow their dates, or else their words; an empty value comes last. The totals of the columns and of the
 * whole still cover every row.
 */
final class QueryAggregatePivots {
    private QueryAggregatePivots() {}

    static QueryAggregateResult order(QueryAggregate aggregate, QueryAggregateResult result) {
        if (result.rows().size() > QueryAggregateShape.MAX_CELLS) {
            throw ApiException.validation(
                    "error.common.report_pivot_cells", Map.of("max", QueryAggregateShape.MAX_CELLS), List.of());
        }
        List<Group> groups = aggregate.groups();
        int rowAt = groups.indexOf(aggregate.explicitGroups().get(0));
        int columnAt = groups.indexOf(aggregate.explicitGroups().get(1));
        List<Integer> implicit = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).implicit()) implicit.add(i);
        List<AggregateTotal> rowTotals = new ArrayList<>();
        List<AggregateTotal> columnTotals = new ArrayList<>();
        List<AggregateTotal> grand = new ArrayList<>();
        for (AggregateTotal total : result.totals()) {
            switch (total.kind()) {
                case QueryAggregateRepository.ROW -> rowTotals.add(total);
                case QueryAggregateRepository.COLUMN -> columnTotals.add(total);
                default -> grand.add(total);
            }
        }
        Set<@Nullable Object> columnValues = new HashSet<>();
        columnTotals.forEach(total -> columnValues.add(total.groups().get(columnAt)));
        if (columnValues.size() > QueryAggregateShape.MAX_COLUMNS) {
            throw ApiException.validation(
                    "error.common.report_pivot_columns", Map.of("max", QueryAggregateShape.MAX_COLUMNS), List.of());
        }
        Collator collator = Collator.getInstance(Locale.forLanguageTag(RequestLanguage.current()));
        collator.setStrength(Collator.SECONDARY);
        rowTotals.sort(rows(aggregate.order(), rowAt, implicit, collator));
        Integer top = aggregate.order().limit();
        int keep = top == null ? QueryAggregate.MAX_ROWS : top;
        boolean truncated = rowTotals.size() > keep;
        List<AggregateTotal> kept = truncated ? new ArrayList<>(rowTotals.subList(0, keep)) : rowTotals;
        Map<List<@Nullable Object>, Integer> rowPlace = new HashMap<>();
        for (int i = 0; i < kept.size(); i++) rowPlace.put(key(kept.get(i).groups(), rowAt, implicit), i);
        boolean dated = groups.get(columnAt).trunc() != null;
        columnTotals.sort(columns(dated, columnAt, implicit, collator));
        Map<@Nullable Object, Integer> columnPlace = new HashMap<>();
        for (AggregateTotal total : columnTotals) {
            columnPlace.putIfAbsent(total.groups().get(columnAt), columnPlace.size());
        }
        List<AggregateRow> cells = new ArrayList<>();
        for (AggregateRow cell : result.rows()) {
            if (rowPlace.containsKey(key(cell.groups(), rowAt, implicit))) cells.add(cell);
        }
        cells.sort(Comparator.<AggregateRow>comparingInt(
                        cell -> rowPlace.getOrDefault(key(cell.groups(), rowAt, implicit), Integer.MAX_VALUE))
                .thenComparingInt(cell -> columnPlace.getOrDefault(cell.groups().get(columnAt), Integer.MAX_VALUE)));
        List<AggregateTotal> totals = new ArrayList<>(kept);
        totals.addAll(columnTotals);
        totals.addAll(grand);
        return result.with(cells, totals, truncated);
    }

    /** The row's value with the currency of money: what tells a row apart. */
    private static List<@Nullable Object> key(List<@Nullable Object> groups, int at, List<Integer> implicit) {
        List<@Nullable Object> key = new ArrayList<>();
        key.add(groups.get(at));
        for (int index : implicit) key.add(groups.get(index));
        return key;
    }

    /** The rows in the report's order; ties and the currency of money by the rows' values. */
    private static Comparator<AggregateTotal> rows(
            QueryAggregate.Order order, int at, List<Integer> implicit, Collator collator) {
        Comparator<AggregateTotal> byValue =
                (left, right) -> values(left.groups().get(at), right.groups().get(at));
        for (int index : implicit) {
            byValue = byValue.thenComparing((left, right) ->
                    values(left.groups().get(index), right.groups().get(index)));
        }
        Comparator<AggregateTotal> first;
        if (order.by() == By.MEASURE) {
            int measure = order.measure();
            first = (left, right) -> nullsLast(
                    QueryAggregateFinish.decimal(left.values().get(measure)),
                    QueryAggregateFinish.decimal(right.values().get(measure)),
                    order.descending());
        } else if (order.by() == By.LABEL) {
            first = (left, right) -> nullsLast(words(left, at), words(right, at), order.descending(), collator);
        } else if (order.descending()) {
            first = (left, right) -> {
                Object a = left.groups().get(at);
                Object b = right.groups().get(at);
                if (a == null || b == null) return values(a, b);
                return values(b, a);
            };
        } else {
            return byValue;
        }
        return first.thenComparing(byValue);
    }

    /** The columns by their dates, or by their words; the empty column last. */
    private static Comparator<AggregateTotal> columns(
            boolean dated, int at, List<Integer> implicit, Collator collator) {
        Comparator<AggregateTotal> order = dated
                ? (left, right) -> values(left.groups().get(at), right.groups().get(at))
                : (left, right) -> nullsLast(words(left, at), words(right, at), false, collator);
        for (int index : implicit) {
            order = order.thenComparing((left, right) ->
                    values(left.groups().get(index), right.groups().get(index)));
        }
        return order;
    }

    private static @Nullable String words(AggregateTotal total, int at) {
        String label = total.labels().get(at);
        if (label != null) return label;
        Object value = total.groups().get(at);
        return value == null ? null : String.valueOf(value);
    }

    /** Values of one group: codes and dates as text, keys as numbers, yes/no; the empty value last. */
    @SuppressWarnings("unchecked")
    static int values(@Nullable Object left, @Nullable Object right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        if (left instanceof Comparable<?> comparable && left.getClass() == right.getClass()) {
            return ((Comparable<Object>) comparable).compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static <T extends Comparable<T>> int nullsLast(@Nullable T left, @Nullable T right, boolean descending) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        int compared = left.compareTo(right);
        return descending ? -compared : compared;
    }

    private static int nullsLast(@Nullable String left, @Nullable String right, boolean descending, Collator collator) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        int compared = collator.compare(left, right);
        return descending ? -compared : compared;
    }
}
