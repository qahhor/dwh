package com.greenwhite.dwh.instance.upl.upload;

import com.greenwhite.dwh.instance.upl.format.UplTemplateBuilder;
import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.ErrorRow;
import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.ErrorsView;
import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.PackageRow;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The errors of one upload as a file the supplier can fix their data from
 * without an operator (roadmap item 21): what was uploaded and what came of
 * it, then every stored error with its address — sheet, row, column — the
 * value found and what is wrong with it in words, not codes. A structure
 * error (a missing sheet or column) has no row. The table has a filter and a
 * fixed header, so a long list can be worked through sheet by sheet.
 */
@Component
public class UplErrorReportBuilder {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);
    private static final String HEADER_FILL = "DCE6F2";

    public record ReportFile(String fileName, byte[] content) {
    }

    /**
     * @param text translation of a dictionary key with its parameters; for an error code it is looked up as
     *             {@code upl.err.<code>}, and a code without a text is shown as it is
     */
    public ReportFile build(PackageRow pkg, ErrorsView errors, BiFunction<String, Map<String, Object>, String> text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, UplTemplateBuilder.XLSX_APPLICATION, UplTemplateBuilder.XLSX_APP_VERSION)) {
            Worksheet ws = workbook.newWorksheet(sheetName(text.apply("upl.errfile.sheet", Map.of())));
            int row = 0;
            ws.value(row, 0, text.apply("upl.errfile.title", Map.of("file", pkg.fileName())));
            ws.style(row, 0).bold().fontSize(14).set();
            row += 2;
            row = fact(ws, row, text.apply("upl.errfile.source", Map.of()), pkg.sourceName() + " (" + pkg.sourceCode() + ")");
            row = fact(ws, row, text.apply("upl.errfile.period", Map.of()), period(pkg));
            row = fact(ws, row, text.apply("upl.errfile.uploaded_at", Map.of()),
                    pkg.uploadedAt() == null ? "" : MOMENT.format(pkg.uploadedAt()) + " UTC");
            row = fact(ws, row, text.apply("upl.errfile.status", Map.of()), text.apply("upl.pkg.status." + pkg.status(), Map.of()));
            row = fact(ws, row, text.apply("upl.errfile.rows", Map.of()), counters(pkg, text));
            if (pkg.rejectCode() != null) {
                row = fact(ws, row, text.apply("upl.errfile.reason", Map.of()), codeText(pkg.rejectCode(), pkg.rejectParams(), text));
            }
            if (errors.items().size() < errors.total()) {
                ws.value(row++, 0, text.apply("upl.errfile.truncated", Map.of("shown", errors.items().size(), "total", errors.total())));
            }
            row++;

            String[] headers = {
                    text.apply("upl.pkg.errors.col.sheet", Map.of()),
                    text.apply("upl.pkg.errors.col.row", Map.of()),
                    text.apply("upl.pkg.errors.col.column", Map.of()),
                    text.apply("upl.pkg.errors.col.value", Map.of()),
                    text.apply("upl.pkg.errors.col.what", Map.of())
            };
            int headerRow = row;
            for (int c = 0; c < headers.length; c++) {
                ws.value(headerRow, c, headers[c]);
                ws.style(headerRow, c).bold().fillColor(HEADER_FILL).set();
            }
            row++;
            if (errors.items().isEmpty()) {
                ws.value(row++, 0, text.apply("upl.errfile.none", Map.of()));
            }
            for (ErrorRow error : errors.items()) {
                ws.value(row, 0, error.sheet());
                if (error.rowNo() != null) {
                    ws.value(row, 1, error.rowNo());
                }
                ws.value(row, 2, error.columnName());
                ws.value(row, 3, error.cellValue());
                ws.value(row, 4, codeText(error.code(), error.params(), text));
                row++;
            }
            if (!errors.items().isEmpty()) {
                ws.setAutoFilter(headerRow, 0, headers.length - 1);
            }
            ws.freezePane(0, headerRow + 1);
            double[] widths = {24, 10, 28, 28, 70};
            for (int c = 0; c < widths.length; c++) {
                ws.width(c, widths[c]);
            }
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ReportFile(reportName(pkg.fileName()), out.toByteArray());
    }

    private static int fact(Worksheet ws, int row, String label, String value) {
        ws.value(row, 0, label);
        ws.style(row, 0).bold().set();
        ws.value(row, 1, value);
        return row + 1;
    }

    private static String period(PackageRow pkg) {
        if (pkg.periodFrom() == null) return "";
        String from = DAY.format(pkg.periodFrom());
        return pkg.periodTo() == null || pkg.periodTo().equals(pkg.periodFrom()) ? from : from + " – " + DAY.format(pkg.periodTo());
    }

    private static String counters(PackageRow pkg, BiFunction<String, Map<String, Object>, String> text) {
        if (pkg.rowsTotal() == null) return text.apply("upl.errfile.not_counted", Map.of());
        return text.apply("upl.errfile.counters", Map.of(
                "total", pkg.rowsTotal(),
                "accepted", pkg.rowsAccepted() == null ? 0 : pkg.rowsAccepted(),
                "rejected", pkg.rowsRejected() == null ? 0 : pkg.rowsRejected()));
    }

    /** The words for a code, as the card shows them; a code the dictionary does not know stays visible as is. */
    static String codeText(String code, Map<String, Object> params, BiFunction<String, Map<String, Object>, String> text) {
        String key = "upl.err." + code;
        String words = text.apply(key, params == null ? Map.of() : params);
        return words.equals(key) ? code : words;
    }

    private static String reportName(String uploaded) {
        String base = uploaded == null ? "upload" : uploaded.replaceFirst("\\.[^.]+$", "");
        return "errors_" + base + ".xlsx";
    }

    private static String sheetName(String name) {
        String safe = name.replaceAll("[\\[\\]:*?/\\\\]", " ").strip();
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }
}
