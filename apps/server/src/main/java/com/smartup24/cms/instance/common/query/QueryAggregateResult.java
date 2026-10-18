package com.smartup24.cms.instance.common.query;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The answer of a report (ADR-0032, 10.2; plan 10/10, item 5.8): its columns and its rows, each row the values of the
 * groups and then of the measures in the columns' order. A group's value is a choice's code, a yes/no, a row key or the
 * first day of a date bucket ({@code 2026-10-01}); empty values form a group of their own, last. A measure is a count
 * or a number, empty for a group whose values are all empty. The rows come in the report's order.
 *
 * <p>An analytic report (ADR-0044) also answers its totals — of each row and each column of a pivot and of the whole,
 * one per currency of money — and the two periods it compares; a pivot's rows are its filled cells, row after row.
 *
 * @param truncated  more groups exist than the answer holds — beyond {@value QueryAggregate#MAX_ROWS} or the top the
 *                   report asks for: the answer holds the first ones in its order; totals still cover every group
 * @param totals     the totals, rows first, then columns, then the whole; empty without totals
 * @param pivot      the first grouping makes the rows, the second the columns
 * @param comparison the periods of a comparison, or null
 */
public record QueryAggregateResult(
        List<AggregateGroup> groups,
        List<AggregateMeasure> measures,
        List<AggregateRow> rows,
        boolean truncated,
        List<AggregateTotal> totals,
        boolean pivot,
        @Nullable AggregateComparison comparison) {

    public QueryAggregateResult {
        groups = List.copyOf(groups);
        measures = List.copyOf(measures);
        rows = List.copyOf(rows);
        totals = List.copyOf(totals);
    }

    /** A flat answer: groups and measures without totals. */
    public QueryAggregateResult(
            List<AggregateGroup> groups, List<AggregateMeasure> measures, List<AggregateRow> rows, boolean truncated) {
        this(groups, measures, rows, truncated, List.of(), false, null);
    }

    /** The same answer with other rows and totals. */
    public QueryAggregateResult with(List<AggregateRow> newRows, List<AggregateTotal> newTotals, boolean cut) {
        return new QueryAggregateResult(groups, measures, newRows, cut, newTotals, pivot, comparison);
    }

    /**
     * A grouping column.
     *
     * @param field    the list field's key
     * @param trunc    the date bucket ({@code month}), or null
     * @param implicit added by the platform: the currency of a money measure
     */
    public record AggregateGroup(String field, @Nullable String trunc, boolean implicit) {}

    /**
     * A measure column.
     *
     * @param op     {@code count}, {@code count_distinct}, {@code sum}, {@code avg}, {@code min} or {@code max}
     * @param field  the list field's key, or null for {@code count}
     * @param share  the part of a total the column shows in percent — {@code total}, {@code row} or {@code column} — or
     *               null for the figure itself
     * @param period of a comparison: {@code current}, {@code previous}, {@code change} or {@code change_percent}; null
     *               without one
     */
    public record AggregateMeasure(
            String op,
            @Nullable String field,
            @Nullable String share,
            @Nullable String period) {
        /** The figure of an operation over a field, without a share or a period. */
        public AggregateMeasure(String op, @Nullable String field) {
            this(op, field, null, null);
        }
    }

    /**
     * One group: the values of its groups and of its measures, in the order of the columns, and the words of its group
     * values where the server names them — the label of a choice or a list item, the name of a user, the title of a
     * record — read in the viewer's language and scope; null where a value has no word (a date, a yes/no, an empty
     * group) or the viewer may not see the record it names.
     */
    public record AggregateRow(
            @ArraySchema(schema = @Schema(types = {"string", "number", "boolean", "null"}))
            List<@Nullable Object> groups,

            @ArraySchema(schema = @Schema(types = {"number", "null"}))
            List<@Nullable Object> values,

            @ArraySchema(schema = @Schema(types = {"string", "null"}))
            List<@Nullable String> labels) {
        public AggregateRow {
            groups = Collections.unmodifiableList(new ArrayList<>(groups));
            values = Collections.unmodifiableList(new ArrayList<>(values));
            labels = Collections.unmodifiableList(new ArrayList<>(labels));
            if (labels.size() != groups.size()) {
                throw new IllegalArgumentException("A group row names each of its group values or none");
            }
        }

        /** A row whose group values have no words yet. */
        public AggregateRow(List<@Nullable Object> groups, List<@Nullable Object> values) {
            this(groups, values, Collections.nCopies(groups.size(), null));
        }
    }

    /**
     * A total: of a row of a pivot ({@code row}: the first group's value kept, the second empty), of a column
     * ({@code column}: the second kept) or of every group ({@code grand}); the currency of money is always kept. Its
     * measures are computed over all the records it covers, a count of different values too.
     */
    public record AggregateTotal(
            @Schema(allowableValues = {"row", "column", "grand"})
            String kind,

            @ArraySchema(schema = @Schema(types = {"string", "number", "boolean", "null"}))
            List<@Nullable Object> groups,

            @ArraySchema(schema = @Schema(types = {"number", "null"}))
            List<@Nullable Object> values,

            @ArraySchema(schema = @Schema(types = {"string", "null"}))
            List<@Nullable String> labels) {
        public AggregateTotal {
            groups = Collections.unmodifiableList(new ArrayList<>(groups));
            values = Collections.unmodifiableList(new ArrayList<>(values));
            labels = Collections.unmodifiableList(new ArrayList<>(labels));
            if (labels.size() != groups.size()) {
                throw new IllegalArgumentException("A total names each of its group values or none");
            }
        }

        /** The total as a row of groups and values. */
        public AggregateRow row() {
            return new AggregateRow(groups, values, labels);
        }

        /** A total of {@code kind} holding {@code row}'s groups, values and words. */
        public static AggregateTotal of(String kind, AggregateRow row) {
            return new AggregateTotal(kind, row.groups(), row.values(), row.labels());
        }
    }

    /**
     * The two periods of a comparison, their first and last days included.
     *
     * @param field   the date or moment field compared
     * @param period  {@code month}, {@code quarter}, {@code year}, {@code ytd} or {@code custom}
     * @param against the period just before ({@code "previous"}) or a year earlier ({@code "year"})
     */
    public record AggregateComparison(
            String field,
            String period,
            String against,
            String currentFrom,
            String currentTo,
            String previousFrom,
            String previousTo) {}
}
