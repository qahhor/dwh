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
 * @param truncated more groups exist than the answer holds — beyond {@value QueryAggregate#MAX_ROWS} or the top the
 *                  report asks for: the answer holds the first ones in its order
 */
public record QueryAggregateResult(
        List<AggregateGroup> groups, List<AggregateMeasure> measures, List<AggregateRow> rows, boolean truncated) {

    public QueryAggregateResult {
        groups = List.copyOf(groups);
        measures = List.copyOf(measures);
        rows = List.copyOf(rows);
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
     * @param op    {@code count}, {@code sum}, {@code avg}, {@code min} or {@code max}
     * @param field the list field's key, or null for {@code count}
     */
    public record AggregateMeasure(String op, @Nullable String field) {}

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
}
