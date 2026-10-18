package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.instance.common.query.QueryAggregate.By;
import com.smartup24.cms.instance.common.query.QueryAggregate.Group;
import com.smartup24.cms.instance.common.query.QueryAggregate.Measure;
import com.smartup24.cms.instance.common.query.QueryAggregateShape.Comparison;
import com.smartup24.cms.instance.common.query.QueryAggregateShape.Range;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The statement of a {@link QueryAggregate} (ADR-0032, 10.2; ADR-0044). Every expression comes from the registry's
 * fields, every value is a parameter.
 *
 * <ul>
 *   <li>A flat report: {@code select <groups>, <measures> ... group by <positions> order by ... limit :q_rows}.
 *   <li>With totals: the same groups as {@code grouping sets} — the groups, and for a pivot also each grouping alone —
 *       always with the currency of money, so amounts of different currencies are never added; {@code grouping(...)}
 *       tells a total ({@code q_t}, a bit per grouping) and {@code row_number()} numbers the groups of each set in the
 *       report's order ({@code q_n}), so the bound keeps the first groups of each set and every total.
 *   <li>With a comparison: each measure twice, over the rows of the current and of the earlier period ({@code filter
 *       (where ...)}), and only the rows of the two periods read.
 * </ul>
 */
final class QueryAggregateSql {
    private QueryAggregateSql() {}

    static QueryPlan.SqlFragment of(QueryAggregate aggregate, QueryPlan.SqlFragment extra) {
        Map<String, Object> params = new LinkedHashMap<>(extra.params());
        QueryPlan.SqlFragment where = aggregate.filter().where();
        params.putAll(where.params());
        Comparison compare = aggregate.shape().compare();
        String current = null;
        String previous = null;
        String periods = "";
        if (compare != null) {
            String value = compare.field().sql();
            current = range(value, "q_cc", compare.current(), params);
            previous = range(value, "q_cp", compare.previous(), params);
            periods = " and ((" + current + ") or (" + previous + "))";
        }
        List<Group> groups = aggregate.groups();
        List<Measure> measures = aggregate.measures();
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            columns.add(groups.get(i).sql() + " as " + QueryAggregate.groupColumn(i));
        }
        for (int i = 0; i < measures.size(); i++) {
            columns.add(measures.get(i).sql(current) + " as " + QueryAggregate.measureColumn(i));
            if (compare != null) {
                columns.add(measures.get(i).sql(previous) + " as " + QueryAggregate.previousColumn(i));
            }
        }
        String body = " from " + aggregate.list().from() + " where 1=1" + extra.sql() + where.sql() + periods;
        if (aggregate.totaled()) {
            return totaled(aggregate, columns, body, current, params);
        }
        StringBuilder sql =
                new StringBuilder("select ").append(String.join(", ", columns)).append(body);
        if (!groups.isEmpty()) {
            List<String> positions = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) positions.add(Integer.toString(i + 1));
            sql.append(" group by ").append(String.join(", ", positions)).append(" order by ");
            QueryAggregate.Order order = aggregate.order();
            if (order.by() == By.MEASURE) {
                sql.append(QueryAggregate.measureColumn(order.measure()))
                        .append(order.descending() ? " desc" : " asc")
                        .append(" nulls last, ");
            }
            sql.append(String.join(" nulls last, ", positions)).append(" nulls last");
        }
        sql.append(" limit :q_rows");
        params.put("q_rows", aggregate.order().cap() + 1);
        return new QueryPlan.SqlFragment(sql.toString(), params);
    }

    /** The statement with the totals: every grouping set numbered in the report's order and bounded on its own. */
    private static QueryPlan.SqlFragment totaled(
            QueryAggregate aggregate,
            List<String> columns,
            String body,
            @Nullable String current,
            Map<String, Object> params) {
        List<String> explicit = new ArrayList<>();
        List<String> implicit = new ArrayList<>();
        for (Group group : aggregate.groups()) {
            (group.implicit() ? implicit : explicit).add(group.sql());
        }
        String grouping = "grouping(" + String.join(", ", explicit) + ")";
        List<String> all = new ArrayList<>(columns);
        all.add(grouping + " as q_t");
        all.add("row_number() over (partition by " + grouping + " order by " + windowOrder(aggregate, current)
                + ") as q_n");
        List<List<String>> sets = new ArrayList<>();
        sets.add(concat(explicit, implicit));
        if (aggregate.shape().pivot()) {
            sets.add(concat(List.of(explicit.get(0)), implicit));
            sets.add(concat(List.of(explicit.get(1)), implicit));
        }
        sets.add(implicit);
        List<String> written =
                sets.stream().map(set -> "(" + String.join(", ", set) + ")").toList();
        String sql = "select * from (select " + String.join(", ", all) + body + " group by grouping sets ("
                + String.join(", ", written) + ")) q where q.q_n <= :q_rows order by q.q_t, q.q_n";
        params.put(
                "q_rows",
                aggregate.shape().pivot()
                        ? QueryAggregateShape.MAX_CELLS + 1
                        : aggregate.order().cap() + 1);
        return new QueryPlan.SqlFragment(sql, params);
    }

    /** The report's order inside each grouping set: by the measure asked for, then by the groups' values. */
    private static String windowOrder(QueryAggregate aggregate, @Nullable String current) {
        List<String> order = new ArrayList<>();
        QueryAggregate.Order asked = aggregate.order();
        if (asked.by() == By.MEASURE && !aggregate.shape().pivot()) {
            order.add(aggregate.measures().get(asked.measure()).sql(current) + (asked.descending() ? " desc" : " asc")
                    + " nulls last");
        }
        for (Group group : aggregate.groups()) order.add(group.sql() + " nulls last");
        return String.join(", ", order);
    }

    /** {@code value >= :from and value < :until}, the bounds bound as parameters named after {@code name}. */
    private static String range(String value, String name, Range range, Map<String, Object> params) {
        params.put(name + "_a", range.from());
        params.put(name + "_b", range.until());
        return value + " >= :" + name + "_a and " + value + " < :" + name + "_b";
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
