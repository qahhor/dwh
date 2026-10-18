package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.common.query.QueryAggregateRepository;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateRow;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateTotal;
import com.smartup24.cms.instance.common.query.QueryList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.dhatim.fastexcel.Worksheet;
import org.jspecify.annotations.Nullable;

/**
 * A pivot as its matrix (ADR-0044): the rows' grouping down the first column — with the currency of money beside it —
 * a column per value of the second grouping and measure, then the rows' totals; the last rows hold the columns' totals
 * and the grand total, one per currency. Values are named as on screen; an empty cell stays blank.
 */
final class AggregatePivotSheet {
    private AggregatePivotSheet() {}

    /** Where the groupings stand in a row and the place and name of each column, in the order of the totals. */
    private record Layout(
            int rowAt, int columnAt, List<Integer> implicit, Map<Object, Integer> places, Map<Object, String> names) {
        static Layout of(QueryAggregateResult result, Function<String, String> text) {
            int rowAt = -1;
            int columnAt = -1;
            List<Integer> implicit = new ArrayList<>();
            for (int i = 0; i < result.groups().size(); i++) {
                if (result.groups().get(i).implicit()) implicit.add(i);
                else if (rowAt < 0) rowAt = i;
                else columnAt = i;
            }
            Map<Object, String> names = new LinkedHashMap<>();
            Map<Object, Integer> places = new HashMap<>();
            for (AggregateTotal total : result.totals()) {
                if (!QueryAggregateRepository.COLUMN.equals(total.kind())) continue;
                Object value = key(total.groups().get(columnAt));
                if (places.containsKey(value)) continue;
                places.put(value, places.size());
                names.put(
                        value, name(total.groups().get(columnAt), total.labels().get(columnAt), text));
            }
            return new Layout(rowAt, columnAt, implicit, places, names);
        }
    }

    /** Writes the matrix from the first row; the number of its columns. */
    static int write(Worksheet sheet, QueryList list, QueryAggregateResult result, Function<String, String> text) {
        Layout layout = Layout.of(result, text);
        List<String> headers = headers(list, result, layout, text);
        AggregateWorkbookWriter.headers(sheet, headers);
        Map<List<Object>, AggregateRow> cells = new HashMap<>();
        for (AggregateRow cell : result.rows()) {
            cells.put(cellKey(cell.groups(), layout.rowAt(), layout.columnAt(), layout.implicit()), cell);
        }
        int row = 1;
        for (AggregateTotal line : result.totals()) {
            if (QueryAggregateRepository.COLUMN.equals(line.kind())) continue;
            line(sheet, row++, result, layout, cells, line, text);
        }
        return headers.size();
    }

    private static List<String> headers(
            QueryList list, QueryAggregateResult result, Layout layout, Function<String, String> text) {
        int measures = result.measures().size();
        List<String> headers = new ArrayList<>();
        headers.add(AggregateWorkbookWriter.groupHeader(list, result.groups().get(layout.rowAt()), text));
        for (int index : layout.implicit()) {
            headers.add(
                    AggregateWorkbookWriter.groupHeader(list, result.groups().get(index), text));
        }
        List<String> columns = new ArrayList<>(layout.names().values());
        columns.add(text.apply("ui.report.total"));
        for (String column : columns) {
            for (var measure : result.measures()) {
                headers.add(
                        measures == 1
                                ? column
                                : column + ": " + AggregateWorkbookWriter.measureHeader(list, measure, text));
            }
        }
        return headers;
    }

    /** A row's line — its cells and its total — or a line of the columns' totals and the grand total. */
    private static void line(
            Worksheet sheet,
            int row,
            QueryAggregateResult result,
            Layout layout,
            Map<List<Object>, AggregateRow> cells,
            AggregateTotal line,
            Function<String, String> text) {
        boolean ofRow = QueryAggregateRepository.ROW.equals(line.kind());
        int rowAt = layout.rowAt();
        String named =
                ofRow ? name(line.groups().get(rowAt), line.labels().get(rowAt), text) : text.apply("ui.report.total");
        sheet.value(row, 0, named);
        if (!ofRow) sheet.style(row, 0).bold().set();
        List<Integer> implicit = layout.implicit();
        for (int i = 0; i < implicit.size(); i++) {
            AggregateWorkbookWriter.value(sheet, row, 1 + i, line.groups().get(implicit.get(i)));
        }
        int measures = result.measures().size();
        int first = 1 + implicit.size();
        for (Map.Entry<Object, Integer> column : layout.places().entrySet()) {
            List<@Nullable Object> values = ofRow
                    ? valuesOf(cells.get(cellKey(line.groups(), rowAt, column.getKey(), implicit)))
                    : columnTotal(result, layout.columnAt(), column.getKey(), line.groups(), implicit);
            for (int m = 0; m < measures; m++) {
                int col = first + column.getValue() * measures + m;
                AggregateWorkbookWriter.value(sheet, row, col, values == null ? null : values.get(m));
            }
        }
        for (int m = 0; m < measures; m++) {
            AggregateWorkbookWriter.value(
                    sheet,
                    row,
                    first + layout.places().size() * measures + m,
                    line.values().get(m));
        }
    }

    private static @Nullable List<@Nullable Object> valuesOf(@Nullable AggregateRow cell) {
        return cell == null ? null : cell.values();
    }

    private static @Nullable List<@Nullable Object> columnTotal(
            QueryAggregateResult result,
            int columnAt,
            Object column,
            List<@Nullable Object> grand,
            List<Integer> implicit) {
        for (AggregateTotal total : result.totals()) {
            if (!QueryAggregateRepository.COLUMN.equals(total.kind())) continue;
            if (!key(total.groups().get(columnAt)).equals(column)) continue;
            boolean sameCurrency = true;
            for (int index : implicit) {
                Object a = total.groups().get(index);
                Object b = grand.get(index);
                sameCurrency &= a == null ? b == null : a.equals(b);
            }
            if (sameCurrency) return total.values();
        }
        return null;
    }

    /** A cell's place: its row's value, its column's value and the currency of money. */
    private static List<Object> cellKey(
            List<@Nullable Object> groups, int rowAt, Object column, List<Integer> implicit) {
        List<Object> key = new ArrayList<>();
        key.add(key(groups.get(rowAt)));
        key.add(column);
        for (int index : implicit) key.add(key(groups.get(index)));
        return key;
    }

    private static List<Object> cellKey(
            List<@Nullable Object> groups, int rowAt, int columnAt, List<Integer> implicit) {
        return cellKey(groups, rowAt, key(groups.get(columnAt)), implicit);
    }

    /** An empty group value as a key of its own. */
    private static Object key(@Nullable Object value) {
        return value == null ? Empty.VALUE : value;
    }

    private static String name(@Nullable Object value, @Nullable String label, Function<String, String> text) {
        if (value == null) return text.apply("ui.report.empty_group");
        return label != null ? label : String.valueOf(value);
    }

    private enum Empty {
        VALUE
    }
}
