package com.greenwhite.dwh.instance.upl.format;

import com.greenwhite.dwh.instance.upl.format.UplFormatModel.Column;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.DataType;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.FileKind;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.FormatVersion;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.MatchBy;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.Sheet;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.Source;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The file a data supplier fills in, built from one version of a source's
 * format (roadmap item 20). An xlsx template has an instruction sheet first —
 * what the file is for, how to fill it and every column with its type, whether
 * it is required, its unit and key format — then one sheet per format sheet
 * with the headers where the parser looks for them, in the order it matches
 * them (by name, or at their file position). Each header cell carries a note
 * on what goes below it; number, integer and date columns are formatted and
 * checked by Excel as the supplier types, so most mistakes are caught before
 * the upload. A CSV format gets its header line in its encoding and delimiter.
 */
@Component
public class UplTemplateBuilder {

    /** Rows below the header that get formats and input checks: far more than a real upload, still a small file. */
    static final int CHECKED_ROWS = 5000;
    /** Who wrote the file, in its properties; UPL files the server writes all say the same. */
    public static final String XLSX_APPLICATION = "SmartupCMS";
    public static final String XLSX_APP_VERSION = "1.0";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String HEADER_FILL = "DCE6F2";

    /** The file and how to name it for the download. */
    public record TemplateFile(String fileName, String contentType, byte[] content) {
    }

    /**
     * @param text translation of a dictionary key with its parameters, in the language the person reads
     */
    public TemplateFile build(Source source, FormatVersion version, BiFunction<String, Map<String, Object>, String> text) {
        String baseName = source.code() + "_v" + version.version();
        if (version.fileKind() == FileKind.CSV) {
            return new TemplateFile(baseName + ".csv", "text/csv", csv(version));
        }
        return new TemplateFile(baseName + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(source, version, text));
    }

    private byte[] xlsx(Source source, FormatVersion version, BiFunction<String, Map<String, Object>, String> text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, XLSX_APPLICATION, XLSX_APP_VERSION)) {
            instruction(workbook.newWorksheet(sheetName(text.apply("upl.template.instruction_sheet", Map.of()))),
                    source, version, text);
            for (Sheet sheet : sorted(version.sheets())) {
                dataSheet(workbook.newWorksheet(sheet.sheetName()), sheet, version.matchColumnsBy(), text);
            }
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private void instruction(Worksheet ws, Source source, FormatVersion version,
                             BiFunction<String, Map<String, Object>, String> text) {
        int row = 0;
        ws.value(row, 0, text.apply("upl.template.title", Map.of("source", source.name(), "version", version.version())));
        ws.style(row, 0).bold().fontSize(14).set();
        row++;
        ws.value(row++, 0, version.validFrom() == null
                ? text.apply("upl.template.draft", Map.of())
                : text.apply("upl.template.valid_from", Map.of("date", DAY.format(version.validFrom()))));
        row++;
        for (String key : List.of("upl.template.how_1", "upl.template.how_2", "upl.template.how_3", "upl.template.how_4")) {
            ws.value(row++, 0, "• " + text.apply(key, Map.of()));
        }
        row++;
        String[] headers = {
                text.apply("upl.template.col.sheet", Map.of()),
                text.apply("upl.format.col.name_in_file", Map.of()),
                text.apply("upl.format.col.type", Map.of()),
                text.apply("upl.format.col.required", Map.of()),
                text.apply("upl.format.col.source_unit", Map.of()),
                text.apply("upl.format.col.key_mask", Map.of()),
                text.apply("upl.format.col.ref_book", Map.of())
        };
        for (int c = 0; c < headers.length; c++) {
            ws.value(row, c, headers[c]);
            ws.style(row, c).bold().fillColor(HEADER_FILL).set();
        }
        row++;
        for (Sheet sheet : sorted(version.sheets())) {
            for (Column column : ordered(sheet.columns(), version.matchColumnsBy())) {
                ws.value(row, 0, sheet.sheetName());
                ws.value(row, 1, column.nameInFile());
                ws.value(row, 2, text.apply(typeKey(column.dataType()), Map.of()));
                ws.value(row, 3, text.apply(column.required() ? "upl.template.yes" : "upl.template.no", Map.of()));
                ws.value(row, 4, column.sourceUnit());
                ws.value(row, 5, column.keyMask());
                ws.value(row, 6, column.refBookCode());
                row++;
            }
        }
        double[] widths = {22, 32, 18, 14, 16, 20, 20};
        for (int c = 0; c < widths.length; c++) {
            ws.width(c, widths[c]);
        }
    }

    private void dataSheet(Worksheet ws, Sheet sheet, MatchBy matchBy, BiFunction<String, Map<String, Object>, String> text) {
        int header = sheet.headerRow() - 1;
        List<Column> columns = ordered(sheet.columns(), matchBy);
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            int c = matchBy == MatchBy.POSITION && column.filePosition() != null ? column.filePosition() - 1 : i;
            ws.value(header, c, column.nameInFile());
            ws.style(header, c).bold().fillColor(HEADER_FILL).wrapText(true).set();
            ws.comment(header, c, note(column, text));
            ws.width(c, Math.max(14, Math.min(40, column.nameInFile().length() + 4)));
            check(ws, column, header + 1, c, text);
        }
        ws.freezePane(0, header + 1);
    }

    /** Formats the data cells of a column and lets Excel refuse a value of the wrong kind. */
    private void check(Worksheet ws, Column column, int firstRow, int c, BiFunction<String, Map<String, Object>, String> text) {
        int lastRow = firstRow + CHECKED_ROWS - 1;
        String cell = cellName(firstRow, c);
        String formula;
        String format;
        String error;
        switch (column.dataType()) {
            case INTEGER -> {
                formula = "AND(ISNUMBER(" + cell + ")," + cell + "=INT(" + cell + "))";
                format = "0";
                error = "upl.template.error.integer";
            }
            case NUMBER -> {
                formula = "ISNUMBER(" + cell + ")";
                format = "0.########";
                error = "upl.template.error.number";
            }
            case DATE -> {
                formula = "ISNUMBER(" + cell + ")";
                format = "dd.mm.yyyy";
                error = "upl.template.error.date";
            }
            default -> {
                return;
            }
        }
        ws.range(firstRow, c, lastRow, c).style().format(format).set();
        ws.range(firstRow, c, lastRow, c).validateWithFormula(formula)
                .allowBlank(true)
                .showErrorMessage(true)
                .errorTitle(text.apply("upl.template.error_title", Map.of()))
                .error(text.apply(error, Map.of()));
    }

    /** What the header's note says: the type, whether it is required, and the unit or key format when there is one. */
    private static String note(Column column, BiFunction<String, Map<String, Object>, String> text) {
        List<String> lines = new ArrayList<>();
        lines.add(text.apply("upl.format.col.type", Map.of()) + ": " + text.apply(typeKey(column.dataType()), Map.of()));
        lines.add(text.apply("upl.format.col.required", Map.of()) + ": "
                + text.apply(column.required() ? "upl.template.yes" : "upl.template.no", Map.of()));
        if (column.sourceUnit() != null && !column.sourceUnit().isBlank()) {
            lines.add(text.apply("upl.format.col.source_unit", Map.of()) + ": " + column.sourceUnit());
        }
        if (column.keyMask() != null && !column.keyMask().isBlank()) {
            lines.add(text.apply("upl.format.col.key_mask", Map.of()) + ": " + column.keyMask());
        }
        if (column.refBookCode() != null && !column.refBookCode().isBlank()) {
            lines.add(text.apply("upl.format.col.ref_book", Map.of()) + ": " + column.refBookCode());
        }
        return String.join("\n", lines);
    }

    private static byte[] csv(FormatVersion version) {
        Sheet sheet = sorted(version.sheets()).stream().findFirst().orElse(null);
        if (sheet == null) {
            return new byte[0];
        }
        String delimiter = version.delimiter() == null || version.delimiter().isEmpty() ? ";" : version.delimiter();
        List<Column> columns = ordered(sheet.columns(), version.matchColumnsBy());
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) line.append(delimiter);
            line.append(csvCell(columns.get(i).nameInFile(), delimiter));
        }
        line.append("\r\n");
        Charset charset = charset(version.encoding());
        byte[] body = line.toString().getBytes(charset);
        if (!StandardCharsets.UTF_8.equals(charset)) {
            return body;
        }
        // A byte order mark tells Excel the file is UTF-8, so Cyrillic headers open readable.
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        return withBom;
    }

    private static String csvCell(String value, String delimiter) {
        if (value.contains(delimiter) || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static Charset charset(String encoding) {
        try {
            return encoding == null || encoding.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static List<Sheet> sorted(List<Sheet> sheets) {
        return sheets.stream().sorted(Comparator.comparingInt(Sheet::ordinal)).toList();
    }

    /** Columns in the order the parser matches them: by file position when it matches by position. */
    static List<Column> ordered(List<Column> columns, MatchBy matchBy) {
        Comparator<Column> order = matchBy == MatchBy.POSITION
                ? Comparator.comparing((Column c) -> c.filePosition() == null ? Integer.MAX_VALUE : c.filePosition())
                        .thenComparingInt(Column::ordinal)
                : Comparator.comparingInt(Column::ordinal);
        return columns.stream().sorted(order).toList();
    }

    private static String typeKey(DataType type) {
        return "upl.format.type." + type.db();
    }

    /** Excel sheet names are at most 31 characters and must not hold []:*?/\ . */
    private static String sheetName(String name) {
        String safe = name.replaceAll("[\\[\\]:*?/\\\\]", " ").strip();
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    /** A1-style name of a cell from zero-based row and column. */
    static String cellName(int row, int column) {
        StringBuilder letters = new StringBuilder();
        int n = column + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            letters.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return letters.toString() + (row + 1);
    }
}
