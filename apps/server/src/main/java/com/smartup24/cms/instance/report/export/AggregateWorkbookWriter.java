package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateGroup;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateMeasure;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.jspecify.annotations.Nullable;

/**
 * Bounded, unmodified aggregate rows in the report's order; chart series reduction never enters an export (ADR-0036).
 * A group value is written by its label when the report names it, raw otherwise.
 */
final class AggregateWorkbookWriter {
    private AggregateWorkbookWriter() {}

    static byte[] write(String entity, QueryList list, QueryAggregateResult result, Function<String, String> text)
            throws IOException {
        var out = new ByteArrayOutputStream();
        try (var workbook = new Workbook(out, ExportWorkbookWriter.APPLICATION, ExportWorkbookWriter.APP_VERSION)) {
            String dataName = sheetName(text.apply("report.aggregate.sheet"), "Report");
            String metadataName = sheetName(text.apply("report.aggregate.metadata"), "Metadata");
            if (dataName.equalsIgnoreCase(metadataName)) metadataName = "Metadata";
            if (dataName.equalsIgnoreCase(metadataName)) metadataName = "Metadata_2";
            Worksheet data = workbook.newWorksheet(dataName);
            if (result.pivot()) {
                AggregatePivotSheet.write(data, list, result, text);
            } else {
                flat(data, list, result, text);
            }
            metadata(workbook.newWorksheet(metadataName), entity, result);
            workbook.finish();
        }
        return out.toByteArray();
    }

    private static String sheetName(String translated, String fallback) {
        String safe = translated.replaceAll("[\\\\/?*\\[\\]:\\p{Cntrl}]", "_").strip();
        if (safe.length() > 31) {
            int end = Character.isHighSurrogate(safe.charAt(30)) ? 30 : 31;
            safe = safe.substring(0, end);
        }
        safe = safe.replaceAll("^[\\p{javaWhitespace}']+|[\\p{javaWhitespace}']+$", "");
        return safe.isBlank() || safe.equalsIgnoreCase("History") ? fallback : safe;
    }

    /** The groups and measures as rows, then the totals of every group, one per currency (ADR-0044). */
    private static void flat(
            Worksheet data, QueryList list, QueryAggregateResult result, Function<String, String> text) {
        List<String> headers = new ArrayList<>();
        for (var group : result.groups()) headers.add(groupHeader(list, group, text));
        for (var measure : result.measures()) headers.add(measureHeader(list, measure, text));
        headers(data, headers);
        int row = 1;
        for (var item : result.rows()) {
            int col = 0;
            for (int group = 0; group < item.groups().size(); group++) {
                String label = item.labels().get(group);
                value(data, row, col++, label != null ? label : item.groups().get(group));
            }
            for (Object value : item.values()) value(data, row, col++, value);
            row++;
        }
        if (row > 1) data.setAutoFilter(0, 0, headers.size() - 1);
        for (var total : result.totals()) {
            int col = 0;
            boolean named = false;
            for (int group = 0; group < total.groups().size(); group++) {
                Object value = total.groups().get(group);
                if (value == null && !named && !result.groups().get(group).implicit()) {
                    data.value(row, col, text.apply("ui.report.total"));
                    data.style(row, col).bold().set();
                    named = true;
                } else {
                    value(data, row, col, value);
                }
                col++;
            }
            for (Object value : total.values()) value(data, row, col++, value);
            row++;
        }
    }

    /** The header row, bold and frozen. */
    static void headers(Worksheet sheet, List<String> headers) {
        for (int c = 0; c < headers.size(); c++) {
            sheet.value(0, c, headers.get(c));
            sheet.style(0, c).bold().fillColor("DCE6F2").set();
            sheet.width(c, 24);
        }
        sheet.freezePane(0, 1);
    }

    static String groupHeader(QueryList list, AggregateGroup group, Function<String, String> text) {
        return label(list, group.field(), text) + (group.trunc() == null ? "" : " (" + group.trunc() + ")");
    }

    /** "Sum: Total", with its share ("share of total, %") or its period ("previous period") (ADR-0044). */
    static String measureHeader(QueryList list, AggregateMeasure measure, Function<String, String> text) {
        String operation = switch (measure.op()) {
            case "count", "count_distinct", "sum", "avg", "min", "max" -> text.apply("ui.report.op." + measure.op());
            default -> throw new IllegalArgumentException("Unknown aggregate operation");
        };
        String header = operation + (measure.field() == null ? "" : ": " + label(list, measure.field(), text));
        if (measure.share() != null) header += " — " + text.apply("ui.report.share." + measure.share());
        if (measure.period() != null) header += " — " + text.apply("ui.report.period." + measure.period());
        return header;
    }

    private static String label(QueryList list, String key, Function<String, String> text) {
        QueryField field = list.viewerField(key)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.report_not_found"));
        return field.label() == null ? text.apply(field.labelKey()) : field.label();
    }

    static void value(Worksheet sheet, int row, int col, @Nullable Object value) {
        if (value == null) return;
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString());
            BigDecimal significant = decimal.stripTrailingZeros();
            int exponent = significant.precision() - significant.scale() - 1;
            if (significant.precision() > 15 || exponent > 308 || exponent < -307) {
                sheet.value(row, col, decimal.toPlainString());
            } else sheet.value(row, col, decimal);
        } else if (value instanceof Boolean flag) sheet.value(row, col, flag);
        else sheet.value(row, col, String.valueOf(value));
    }

    private static void metadata(Worksheet sheet, String entity, QueryAggregateResult result) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("entity", entity));
        rows.add(List.of("timeBasis", "INSTANT: UTC; DATE: calendar date"));
        rows.add(List.of("NULL", "blank cell; distinct from zero"));
        rows.add(List.of("truncated", Boolean.toString(result.truncated())));
        rows.add(List.of("pivot", Boolean.toString(result.pivot())));
        rows.add(List.of("totals", result.totals().isEmpty() ? "none" : "every group, one per currency"));
        rows.add(List.of("shares", "Percent of the total, rounded half up to 2 decimals; blank when the total is 0"));
        var compare = result.comparison();
        if (compare != null) {
            rows.add(List.of(
                    "comparison",
                    compare.field() + ": " + compare.currentFrom() + ".." + compare.currentTo() + " against "
                            + compare.previousFrom() + ".." + compare.previousTo() + " (" + compare.period() + ", "
                            + compare.against() + "); change in percent of the earlier figure, blank when it is 0"));
        }
        rows.add(List.of("rows", Integer.toString(result.rows().size())));
        rows.add(List.of("precision", "More than 15 significant digits are stored as text"));
        rows.add(List.of(
                "groups",
                "Labels of choices, list items, users and records in the export language and the reader's scope;"
                        + " a value without one is written raw; date buckets start in UTC for INSTANT"));
        int column = 1;
        for (var group : result.groups()) {
            rows.add(List.of(
                    "column " + column++,
                    "group: " + group.field() + "; bucket: " + (group.trunc() == null ? "none" : group.trunc())
                            + "; implicit: " + group.implicit()));
        }
        for (var measure : result.measures()) {
            rows.add(List.of(
                    "column " + column++,
                    "measure: " + measure.op() + (measure.field() == null ? "" : "; field: " + measure.field())
                            + (measure.share() == null ? "" : "; share: " + measure.share())
                            + (measure.period() == null ? "" : "; period: " + measure.period())));
        }
        for (int row = 0; row < rows.size(); row++) {
            sheet.value(row, 0, rows.get(row).get(0));
            sheet.value(row, 1, rows.get(row).get(1));
        }
        sheet.width(0, 20);
        sheet.width(1, 90);
    }
}
