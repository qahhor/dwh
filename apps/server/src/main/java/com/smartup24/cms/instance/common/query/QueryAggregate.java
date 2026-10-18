package com.smartup24.cms.instance.common.query;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A validated aggregate query over a list (ADR-0032, 10.2; plan 10/10, item 5.8): the groups, the measures and the
 * filter of a report. Built only by {@link QueryAggregates}: every expression comes from the registry's fields, the
 * date buckets and operations from fixed sets, the filter values go as parameters — nothing a client sends becomes
 * SQL text.
 *
 * @param list     the list the report reads
 * @param groups   what the rows are grouped by, the implicit currency of money measures last
 * @param measures what each group answers, in the requested order
 * @param filter   the list's filter, validated as for a page; its sort, page and cursor are not used
 * @param order    how the groups are ordered and how many are kept
 * @param shape    the pivot, the totals and the period comparison of the report (ADR-0044)
 */
public record QueryAggregate(
        QueryList list,
        List<Group> groups,
        List<Measure> measures,
        QueryPlan filter,
        Order order,
        QueryAggregateShape shape) {

    /** No chart or table shows more groups; the query reads one more to tell that the answer was cut. */
    public static final int MAX_ROWS = 1000;

    /** A date or a moment rounded down to the start of its bucket; a moment in UTC. */
    public enum Trunc {
        DAY,
        WEEK,
        MONTH,
        QUARTER,
        YEAR;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Trunc> fromWire(String text) {
            for (Trunc trunc : values()) {
                if (trunc.wire().equals(text)) return Optional.of(trunc);
            }
            return Optional.empty();
        }
    }

    /**
     * What a group answers. {@code count} takes no field; {@code count_distinct} the different values of a field
     * (ADR-0044); the others a number or the amount of money.
     */
    public enum Op {
        COUNT,
        COUNT_DISTINCT,
        SUM,
        AVG,
        MIN,
        MAX;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Op> fromWire(String text) {
            for (Op op : values()) {
                if (op.wire().equals(text)) return Optional.of(op);
            }
            return Optional.empty();
        }
    }

    /**
     * A grouping field, with its date bucket for a date or a moment.
     *
     * @param implicit added by the platform: the currency of a money measure, so amounts in different currencies are
     *                 never summed together
     */
    public record Group(QueryField field, @Nullable Trunc trunc, boolean implicit) {

        public Group {
            Objects.requireNonNull(field, "field");
            boolean dated = field.type() == QueryFieldType.DATE || field.type() == QueryFieldType.INSTANT;
            if (dated != (trunc != null)) {
                throw new IllegalArgumentException("Group " + field.key() + ": a date bucket goes with a date only");
            }
        }

        /** The group's value over the list's {@code from}. */
        String sql() {
            if (trunc == null) return field.sql();
            String unit = "'" + trunc.wire() + "'";
            String timestamp = field.type() == QueryFieldType.INSTANT
                    ? "(" + field.sql() + ") at time zone 'UTC'"
                    : "cast(" + field.sql() + " as timestamp)";
            return "cast(date_trunc(" + unit + ", " + timestamp + ") as date)";
        }
    }

    /**
     * The part of a total a measure shows (ADR-0044): of the grand total, of its row's total or of its column's total in
     * a pivot, in percent.
     */
    public enum Share {
        TOTAL,
        ROW,
        COLUMN;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Share> fromWire(String text) {
            for (Share share : values()) {
                if (share.wire().equals(text)) return Optional.of(share);
            }
            return Optional.empty();
        }
    }

    /**
     * A measure: an operation over a field, or the count of the group's rows; with a share, the part of a total its
     * count or sum is (ADR-0044).
     */
    public record Measure(
            Op op, @Nullable QueryField field, @Nullable Share share) {

        public Measure(Op op, @Nullable QueryField field) {
            this(op, field, null);
        }

        public Measure {
            Objects.requireNonNull(op, "op");
            if ((op == Op.COUNT) != (field == null)) {
                throw new IllegalArgumentException("Measure " + op.wire() + ": only count goes without a field");
            }
            if (share != null && op != Op.COUNT && op != Op.SUM) {
                throw new IllegalArgumentException("Measure " + op.wire() + ": only a count or a sum has a share");
            }
        }

        String sql() {
            return sql(null);
        }

        /** The aggregate over the rows that meet {@code condition}, or over every row of the group without one. */
        String sql(@Nullable String condition) {
            String only = condition == null ? "" : " filter (where " + condition + ")";
            if (op == Op.COUNT) return "count(*)" + only;
            String value = Objects.requireNonNull(field, "field").sql();
            return switch (op) {
                case COUNT_DISTINCT -> "count(distinct " + value + ")" + only;
                case SUM -> "sum(" + value + ")" + only;
                case AVG -> "round(avg(" + value + ")" + only + ", 4)";
                case MIN -> "min(" + value + ")" + only;
                case MAX -> "max(" + value + ")" + only;
                case COUNT -> throw new IllegalStateException("count has no field");
            };
        }

        /** Whether the answer is a whole number: a count of rows or of different values. */
        public boolean counted() {
            return op == Op.COUNT || op == Op.COUNT_DISTINCT;
        }

        /** The field's key, or null for {@code count}. */
        public @Nullable String fieldKey() {
            return field == null ? null : field.key();
        }
    }

    /** What the groups are ordered by: their values, their words or a measure. */
    public enum By {
        GROUP,
        LABEL,
        MEASURE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<By> fromWire(String text) {
            for (By by : values()) {
                if (by.wire().equals(text)) return Optional.of(by);
            }
            return Optional.empty();
        }
    }

    /**
     * The order of the groups and the top of them a report keeps (plan 10/10, the form and report builder): by their
     * values (the default: a choice's code, a row key, a date), by their words — the labels of choices, list items, users
     * and records, ordered once the words are read — or by a measure; at most {@code limit} groups when it is given.
     *
     * @param measure the position of the measure ordered by, for {@link By#MEASURE}
     */
    public record Order(
            By by,
            int measure,
            boolean descending,
            @Nullable Integer limit) {
        /** The groups by their values, ascending, as many as {@value #MAX_ROWS}. */
        public static final Order DEFAULT = new Order(By.GROUP, 0, false, null);

        public Order {
            Objects.requireNonNull(by, "by");
            if (limit != null && (limit < 1 || limit > MAX_ROWS)) {
                throw new IllegalArgumentException("A report keeps 1 to " + MAX_ROWS + " groups");
            }
        }

        /** The groups the query reads before the words are known: every one for an order by words. */
        public int cap() {
            return limit == null || by == By.LABEL ? MAX_ROWS : limit;
        }
    }

    /** An aggregate in the default order. */
    public QueryAggregate(QueryList list, List<Group> groups, List<Measure> measures, QueryPlan filter) {
        this(list, groups, measures, filter, Order.DEFAULT);
    }

    /** A flat aggregate: groups and measures, no pivot, totals or comparison. */
    public QueryAggregate(QueryList list, List<Group> groups, List<Measure> measures, QueryPlan filter, Order order) {
        this(list, groups, measures, filter, order, QueryAggregateShape.FLAT);
    }

    public QueryAggregate {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(shape, "shape");
        if (order.by() == By.MEASURE && order.measure() >= measures.size()) {
            throw new IllegalArgumentException("The order names a measure the aggregate does not answer");
        }
        groups = List.copyOf(groups);
        measures = List.copyOf(measures);
        if (measures.isEmpty()) {
            throw new IllegalArgumentException("An aggregate answers at least one measure");
        }
        if (shape.pivot() && groups.stream().filter(group -> !group.implicit()).count() != 2) {
            throw new IllegalArgumentException("A pivot has exactly two groupings: its rows and its columns");
        }
    }

    /** The groups the report asks for, without the currency the platform adds. */
    public List<Group> explicitGroups() {
        return groups.stream().filter(group -> !group.implicit()).toList();
    }

    /**
     * Whether the statement also reads the totals (ADR-0044): a pivot, a share or totals asked for, over at least one
     * grouping — a report without one is its own total.
     */
    public boolean totaled() {
        boolean wanted =
                shape.pivot() || shape.totals() || measures.stream().anyMatch(measure -> measure.share() != null);
        return wanted && !explicitGroups().isEmpty();
    }

    /** The column of the {@code i}-th group in the query's answer. */
    static String groupColumn(int i) {
        return "q_g" + i;
    }

    /** The column of the {@code i}-th measure in the query's answer, over the current period of a comparison. */
    static String measureColumn(int i) {
        return "q_m" + i;
    }

    /** The column of the {@code i}-th measure over the earlier period of a comparison. */
    static String previousColumn(int i) {
        return "q_p" + i;
    }

    /**
     * The aggregate as SQL ({@link QueryAggregateSql}): {@code select <groups>, <measures> from <list> where <extra>
     * <filter> group by <groups> order by [<measure>,] <groups> limit} the {@link Order#cap() cap} + 1, or the
     * statement of the totals and periods of an analytic shape.
     *
     * @param extra the data scope of the viewer ({@code " and ..."}), its parameters not prefixed {@code q_}
     */
    public QueryPlan.SqlFragment sql(QueryPlan.SqlFragment extra) {
        return QueryAggregateSql.of(this, extra);
    }
}
