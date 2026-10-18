package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.instance.common.query.QueryAggregate.Group;
import com.smartup24.cms.instance.common.query.QueryAggregate.Measure;
import com.smartup24.cms.instance.common.query.QueryAggregate.Share;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateComparison;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateMeasure;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateRow;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateTotal;
import com.smartup24.cms.instance.common.query.QueryAggregateShape.Comparison;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the server computes over the figures one statement returned (ADR-0044) — never over records: the change between
 * two periods, the share of a figure in its total and the order of a pivot ({@link QueryAggregatePivots}). A share and a
 * change in percent have {@value #SCALE} decimals, rounded half up; a division by zero or by an empty figure gives an
 * empty value, not an error.
 */
public final class QueryAggregateFinish {

    /** The decimals of a percent. */
    public static final int SCALE = 2;

    public static final String CURRENT = "current";
    public static final String PREVIOUS = "previous";
    public static final String CHANGE = "change";
    public static final String CHANGE_PERCENT = "change_percent";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private QueryAggregateFinish() {}

    /** The finished answer: a pivot ordered and cut to its rows, the shares in percent of their totals. */
    public static QueryAggregateResult finish(QueryAggregate aggregate, QueryAggregateResult labeled) {
        QueryAggregateResult ordered =
                aggregate.shape().pivot() ? QueryAggregatePivots.order(aggregate, labeled) : labeled;
        return shares(aggregate, ordered);
    }

    /** The columns of the measures: one per measure, or four per measure of a comparison. */
    static List<AggregateMeasure> columns(QueryAggregate aggregate) {
        List<AggregateMeasure> columns = new ArrayList<>();
        boolean compared = aggregate.shape().compare() != null;
        for (Measure measure : aggregate.measures()) {
            String op = measure.op().wire();
            String field = measure.fieldKey();
            if (!compared) {
                columns.add(new AggregateMeasure(
                        op,
                        field,
                        measure.share() == null ? null : measure.share().wire(),
                        null));
                continue;
            }
            for (String period : List.of(CURRENT, PREVIOUS, CHANGE, CHANGE_PERCENT)) {
                columns.add(new AggregateMeasure(op, field, null, period));
            }
        }
        return List.copyOf(columns);
    }

    /** The periods of a comparison as the answer names them, or null. */
    static @Nullable AggregateComparison comparison(QueryAggregate aggregate) {
        Comparison compare = aggregate.shape().compare();
        if (compare == null) return null;
        return new AggregateComparison(
                compare.field().key(),
                compare.period().wire(),
                compare.against().wire(),
                compare.current().first().toString(),
                compare.current().last().toString(),
                compare.previous().first().toString(),
                compare.previous().last().toString());
    }

    /**
     * A measure over two periods: the current figure, the earlier one, the change and the change in percent of the
     * earlier figure. Each is empty when a figure it needs is empty; the percent also when the earlier figure is zero.
     */
    static List<@Nullable Object> periods(Measure measure, @Nullable Object current, @Nullable Object previous) {
        BigDecimal now = decimal(current);
        BigDecimal before = decimal(previous);
        Object change = null;
        BigDecimal percent = null;
        if (now != null && before != null) {
            BigDecimal difference = now.subtract(before);
            change = measure.counted() ? (Object) Long.valueOf(difference.longValueExact()) : difference;
            if (before.signum() != 0) {
                percent = difference.multiply(HUNDRED).divide(before.abs(), SCALE, RoundingMode.HALF_UP);
            }
        }
        return Arrays.asList(current, previous, change, percent);
    }

    /** {@code value} in percent of {@code total}; empty when either is empty or the total is zero. */
    static @Nullable BigDecimal percent(@Nullable Object value, @Nullable Object total) {
        BigDecimal part = decimal(value);
        BigDecimal whole = decimal(total);
        if (part == null || whole == null || whole.signum() == 0) return null;
        return part.multiply(HUNDRED).divide(whole, SCALE, RoundingMode.HALF_UP);
    }

    static @Nullable BigDecimal decimal(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(value.toString());
    }

    /**
     * The measures with a share as their part of a total: of the whole (by currency), of the row's total or of the
     * column's total of a pivot. A total's own share: a row's total of the whole, of itself (100) or of the whole; a
     * column's likewise.
     */
    static QueryAggregateResult shares(QueryAggregate aggregate, QueryAggregateResult result) {
        if (aggregate.measures().stream().allMatch(measure -> measure.share() == null)) return result;
        Keys keys = new Keys(aggregate.groups());
        Map<List<@Nullable Object>, AggregateTotal> grand = new HashMap<>();
        Map<List<@Nullable Object>, AggregateTotal> rows = new HashMap<>();
        Map<List<@Nullable Object>, AggregateTotal> columns = new HashMap<>();
        for (AggregateTotal total : result.totals()) {
            switch (total.kind()) {
                case QueryAggregateRepository.ROW -> rows.put(keys.of(total.groups(), keys.row), total);
                case QueryAggregateRepository.COLUMN -> columns.put(keys.of(total.groups(), keys.column), total);
                default -> grand.put(keys.of(total.groups(), -1), total);
            }
        }
        List<AggregateRow> cells = new ArrayList<>();
        for (AggregateRow row : result.rows()) {
            AggregateTotal whole = grand.get(keys.of(row.groups(), -1));
            AggregateTotal ofRow = rows.get(keys.of(row.groups(), keys.row));
            AggregateTotal ofColumn = columns.get(keys.of(row.groups(), keys.column));
            cells.add(withShares(aggregate, row, whole, ofRow, ofColumn));
        }
        List<AggregateTotal> totals = new ArrayList<>();
        for (AggregateTotal total : result.totals()) {
            AggregateTotal whole = grand.get(keys.of(total.groups(), -1));
            AggregateRow own = total.row();
            AggregateRow shared = switch (total.kind()) {
                case QueryAggregateRepository.ROW -> withShares(aggregate, own, whole, total, whole);
                case QueryAggregateRepository.COLUMN -> withShares(aggregate, own, whole, whole, total);
                default -> withShares(aggregate, own, total, total, total);
            };
            totals.add(AggregateTotal.of(total.kind(), shared));
        }
        return result.with(cells, totals, result.truncated());
    }

    private static AggregateRow withShares(
            QueryAggregate aggregate,
            AggregateRow row,
            @Nullable AggregateTotal whole,
            @Nullable AggregateTotal ofRow,
            @Nullable AggregateTotal ofColumn) {
        List<@Nullable Object> values = new ArrayList<>(row.values());
        for (int i = 0; i < aggregate.measures().size(); i++) {
            Share share = aggregate.measures().get(i).share();
            if (share == null) continue;
            AggregateTotal base = switch (share) {
                case TOTAL -> whole;
                case ROW -> ofRow;
                case COLUMN -> ofColumn;
            };
            values.set(
                    i,
                    base == null
                            ? null
                            : percent(row.values().get(i), base.values().get(i)));
        }
        return new AggregateRow(row.groups(), values, row.labels());
    }

    /** The keys of a row in the totals: the currency of money, with the row's or the column's group of a pivot. */
    private static final class Keys {
        private final List<Integer> implicit = new ArrayList<>();
        private final int row;
        private final int column;

        Keys(List<Group> groups) {
            List<Integer> explicit = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                (groups.get(i).implicit() ? implicit : explicit).add(i);
            }
            row = explicit.isEmpty() ? -1 : explicit.get(0);
            column = explicit.size() < 2 ? -1 : explicit.get(1);
        }

        List<@Nullable Object> of(List<@Nullable Object> groups, int kept) {
            List<@Nullable Object> key = new ArrayList<>();
            if (kept >= 0) key.add(groups.get(kept));
            for (int index : implicit) key.add(groups.get(index));
            return key;
        }
    }
}
