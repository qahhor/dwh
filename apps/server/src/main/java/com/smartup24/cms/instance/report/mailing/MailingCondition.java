package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateMeasure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * When a run sends to a recipient (ADR-0045), judged on that recipient's own result of the report.
 *
 * <ul>
 *   <li>{@code always};
 *   <li>{@code has_rows}: a grouped report has a group; a report without groups has a figure that is neither empty nor
 *       zero (it always answers its one row, counting nothing as 0);
 *   <li>{@code threshold}: the grand total of a measure — the n-th measure of the saved report, the current period of a
 *       comparison — compares to a value; with totals in several currencies one of them is enough. The grand total is
 *       the report's total, or its only row when it has no groups; a grouped report without totals has none, so the
 *       mailing is refused when saved.
 * </ul>
 *
 * @param measure threshold: the measure's position in the saved report
 * @param op      threshold: {@code gt}, {@code gte}, {@code lt}, {@code lte} or {@code eq}
 */
public record MailingCondition(
        String kind,
        @Nullable Integer measure,
        @Nullable String op,
        @Nullable BigDecimal value) {

    public static final String ALWAYS = "always";
    public static final String HAS_ROWS = "has_rows";
    public static final String THRESHOLD = "threshold";
    public static final List<String> OPS = List.of("gt", "gte", "lt", "lte", "eq");

    public static MailingCondition always() {
        return new MailingCondition(ALWAYS, null, null, null);
    }

    /** Whether the recipient's result meets the condition. */
    public boolean holds(QueryAggregateResult result) {
        return switch (kind) {
            case HAS_ROWS -> hasRows(result);
            case THRESHOLD -> grandValues(result).stream().anyMatch(this::compares);
            default -> true;
        };
    }

    static boolean hasRows(QueryAggregateResult result) {
        if (!result.groups().stream().allMatch(QueryAggregateResult.AggregateGroup::implicit)) {
            return !result.rows().isEmpty();
        }
        return result.rows().stream()
                .flatMap(row -> row.values().stream())
                .anyMatch(value -> value instanceof Number number && new BigDecimal(number.toString()).signum() != 0);
    }

    private boolean compares(BigDecimal figure) {
        int order = figure.compareTo(Objects.requireNonNull(value, "value"));
        return switch (Objects.requireNonNull(op, "op")) {
            case "gt" -> order > 0;
            case "gte" -> order >= 0;
            case "lt" -> order < 0;
            case "lte" -> order <= 0;
            default -> order == 0;
        };
    }

    /** The grand totals of the measure, one per currency; empty when the result has none. */
    List<BigDecimal> grandValues(QueryAggregateResult result) {
        int column = column(result.measures(), Objects.requireNonNull(measure, "measure"));
        List<BigDecimal> values = new ArrayList<>();
        if (column < 0) return values;
        List<List<@Nullable Object>> rows = new ArrayList<>();
        result.totals().stream()
                .filter(total -> "grand".equals(total.kind()))
                .forEach(total -> rows.add(total.values()));
        if (rows.isEmpty() && result.groups().stream().allMatch(QueryAggregateResult.AggregateGroup::implicit)) {
            result.rows().forEach(row -> rows.add(row.values()));
        }
        for (List<@Nullable Object> row : rows) {
            Object figure = column < row.size() ? row.get(column) : null;
            if (figure instanceof Number number) values.add(new BigDecimal(number.toString()));
        }
        return values;
    }

    /** The column of the n-th measure of the saved report: a comparison answers each measure in four columns. */
    static int column(List<AggregateMeasure> measures, int position) {
        int seen = 0;
        for (int index = 0; index < measures.size(); index++) {
            String period = measures.get(index).period();
            if (period != null && !"current".equals(period)) continue;
            if (seen++ == position) return index;
        }
        return -1;
    }
}
