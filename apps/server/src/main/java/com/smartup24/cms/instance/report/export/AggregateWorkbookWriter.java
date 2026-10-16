package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
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

/** Bounded, unmodified aggregate rows; chart series reduction never enters an export (ADR-0036). */
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
            List<String> headers = headers(list, result, text);
            for (int c = 0; c < headers.size(); c++) {
                data.value(0, c, headers.get(c));
                data.style(0, c).bold().fillColor("DCE6F2").set();
                data.width(c, 24);
            }
            data.freezePane(0, 1);
            int row = 1;
            for (var item : result.rows()) {
                int col = 0;
                for (Object value : item.groups()) value(data, row, col++, value);
                for (Object value : item.values()) value(data, row, col++, value);
                row++;
            }
            if (row > 1) data.setAutoFilter(0, 0, headers.size() - 1);
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

    private static List<String> headers(QueryList list, QueryAggregateResult result, Function<String, String> text) {
        List<String> resultHeaders = new ArrayList<>();
        for (var group : result.groups()) {
            resultHeaders.add(
                    label(list, group.field(), text) + (group.trunc() == null ? "" : " (" + group.trunc() + ")"));
        }
        for (var measure : result.measures()) {
            String operation = switch (measure.op()) {
                case "count" -> text.apply("ui.report.op.count");
                case "sum" -> text.apply("ui.report.op.sum");
                case "avg" -> text.apply("ui.report.op.avg");
                case "min" -> text.apply("ui.report.op.min");
                case "max" -> text.apply("ui.report.op.max");
                default -> throw new IllegalArgumentException("Unknown aggregate operation");
            };
            resultHeaders.add(operation + (measure.field() == null ? "" : ": " + label(list, measure.field(), text)));
        }
        return resultHeaders;
    }

    private static String label(QueryList list, String key, Function<String, String> text) {
        QueryField field = list.viewerField(key)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.report_not_found"));
        return field.label() == null ? text.apply(field.labelKey()) : field.label();
    }

    private static void value(Worksheet sheet, int row, int col, @Nullable Object value) {
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
        rows.add(List.of("rows", Integer.toString(result.rows().size())));
        rows.add(List.of("precision", "More than 15 significant digits are stored as text"));
        rows.add(List.of("groups", "Raw enum codes and reference IDs; date buckets start in UTC for INSTANT"));
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
                    "measure: " + measure.op() + (measure.field() == null ? "" : "; field: " + measure.field())));
        }
        for (int row = 0; row < rows.size(); row++) {
            sheet.value(row, 0, rows.get(row).get(0));
            sheet.value(row, 1, rows.get(row).get(1));
        }
        sheet.width(0, 20);
        sheet.width(1, 90);
    }
}
