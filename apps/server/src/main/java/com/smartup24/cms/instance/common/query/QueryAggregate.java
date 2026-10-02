package com.smartup24.cms.instance.common.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 */
public record QueryAggregate(QueryList list, List<Group> groups, List<Measure> measures, QueryPlan filter) {

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

    /** What a group answers. {@code count} takes no field; the others a number or the amount of money. */
    public enum Op {
        COUNT,
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

    /** A measure: an operation over a numeric field, or the count of the group's rows. */
    public record Measure(Op op, @Nullable QueryField field) {

        public Measure {
            Objects.requireNonNull(op, "op");
            if ((op == Op.COUNT) != (field == null)) {
                throw new IllegalArgumentException("Measure " + op.wire() + ": only count goes without a field");
            }
        }

        String sql() {
            if (op == Op.COUNT) return "count(*)";
            String value = Objects.requireNonNull(field, "field").sql();
            return switch (op) {
                case SUM -> "sum(" + value + ")";
                case AVG -> "round(avg(" + value + "), 4)";
                case MIN -> "min(" + value + ")";
                case MAX -> "max(" + value + ")";
                case COUNT -> throw new IllegalStateException("count has no field");
            };
        }

        /** The field's key, or null for {@code count}. */
        public @Nullable String fieldKey() {
            return field == null ? null : field.key();
        }
    }

    public QueryAggregate {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(filter, "filter");
        groups = List.copyOf(groups);
        measures = List.copyOf(measures);
        if (measures.isEmpty()) {
            throw new IllegalArgumentException("An aggregate answers at least one measure");
        }
    }

    /** The column of the {@code i}-th group in the query's answer. */
    static String groupColumn(int i) {
        return "q_g" + i;
    }

    /** The column of the {@code i}-th measure in the query's answer. */
    static String measureColumn(int i) {
        return "q_m" + i;
    }

    /**
     * The aggregate as SQL: {@code select <groups>, <measures> from <list> where <extra> <filter> group by <groups>
     * order by <groups> limit} {@value #MAX_ROWS} + 1. Groups are numbered by position, so each expression is written
     * once.
     *
     * @param extra the data scope of the viewer ({@code " and ..."}), its parameters not prefixed {@code q_}
     */
    public QueryPlan.SqlFragment sql(QueryPlan.SqlFragment extra) {
        List<String> columns = new ArrayList<>();
        List<String> positions = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            columns.add(groups.get(i).sql() + " as " + groupColumn(i));
            positions.add(Integer.toString(i + 1));
        }
        for (int i = 0; i < measures.size(); i++) {
            columns.add(measures.get(i).sql() + " as " + measureColumn(i));
        }
        QueryPlan.SqlFragment where = filter.where();
        StringBuilder sql = new StringBuilder("select ")
                .append(String.join(", ", columns))
                .append(" from ")
                .append(list.from())
                .append(" where 1=1")
                .append(extra.sql())
                .append(where.sql());
        if (!positions.isEmpty()) {
            sql.append(" group by ").append(String.join(", ", positions));
            sql.append(" order by ")
                    .append(String.join(" nulls last, ", positions))
                    .append(" nulls last");
        }
        sql.append(" limit :q_rows");
        Map<String, Object> params = new LinkedHashMap<>(extra.params());
        params.putAll(where.params());
        params.put("q_rows", MAX_ROWS + 1);
        return new QueryPlan.SqlFragment(sql.toString(), params);
    }
}
