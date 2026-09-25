package com.greenwhite.dwh.instance.report.export;

import com.greenwhite.dwh.instance.common.query.QueryField;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One export as an xlsx sheet (ADR-0018): a header row of the list's column
 * titles, then a row per item. Values keep their kind — numbers as numbers,
 * dates and moments as Excel dates in UTC, flags as yes/no, choices by their
 * words — so the file can be sorted and summed at once.
 */
final class ExportWorkbookWriter implements AutoCloseable {

    /** Who wrote the file, in its properties; the format wants the version as XX.YYYY. */
    static final String APPLICATION = "SmartupCMS";
    static final String APP_VERSION = "1.0";
    private static final String HEADER_FILL = "DCE6F2";
    private static final String DATE_FORMAT = "dd.mm.yyyy";
    private static final String MOMENT_FORMAT = "dd.mm.yyyy hh:mm";

    private final Workbook workbook;
    private final Worksheet sheet;
    private final List<QueryField> fields;
    private final Function<String, String> text;
    private int row;

    ExportWorkbookWriter(OutputStream out, String sheetName, List<QueryField> fields, Function<String, String> text,
                         String application, String version) {
        this.workbook = new Workbook(out, application, version);
        this.sheet = workbook.newWorksheet(sheetName(sheetName));
        this.fields = List.copyOf(fields);
        this.text = text;
        for (int c = 0; c < fields.size(); c++) {
            sheet.value(0, c, text.apply(fields.get(c).labelKey()));
            sheet.style(0, c).bold().fillColor(HEADER_FILL).set();
            sheet.width(c, width(fields.get(c)));
        }
        sheet.freezePane(0, 1);
        row = 1;
    }

    /** One item, read by field key (the item's JSON property names). */
    void add(Map<String, Object> item) {
        for (int c = 0; c < fields.size(); c++) {
            QueryField field = fields.get(c);
            Object value = item.get(field.key());
            if (value == null) continue;
            switch (field.type()) {
                case NUMBER -> {
                    if (value instanceof Number number) sheet.value(row, c, number);
                    else sheet.value(row, c, String.valueOf(value));
                }
                case DATE -> {
                    sheet.value(row, c, LocalDate.parse(String.valueOf(value)));
                    sheet.style(row, c).format(DATE_FORMAT).set();
                }
                case INSTANT -> {
                    sheet.value(row, c, Instant.parse(String.valueOf(value)).atZone(ZoneOffset.UTC).toLocalDateTime());
                    sheet.style(row, c).format(MOMENT_FORMAT).set();
                }
                case BOOLEAN -> sheet.value(row, c, text.apply(Boolean.TRUE.equals(value) ? "common.yes" : "common.no"));
                case ENUM -> sheet.value(row, c, enumWords(field, String.valueOf(value)));
                default -> sheet.value(row, c, String.valueOf(value));
            }
        }
        row++;
    }

    int rows() {
        return row - 1;
    }

    @Override
    public void close() throws IOException {
        if (row > 1) {
            sheet.setAutoFilter(0, 0, fields.size() - 1);
        }
        workbook.finish();
        workbook.close();
    }

    private String enumWords(QueryField field, String value) {
        if (field.enumLabelPrefix() == null) return value;
        String key = field.enumLabelPrefix() + value;
        String words = text.apply(key);
        return words.equals(key) ? value : words;
    }

    private static double width(QueryField field) {
        return switch (field.type()) {
            case NUMBER, BOOLEAN -> 14;
            case DATE -> 14;
            case INSTANT -> 18;
            default -> 28;
        };
    }

    private static String sheetName(String name) {
        String safe = name.replaceAll("[\\[\\]:*?/\\\\]", " ").strip();
        if (safe.isEmpty()) safe = "export";
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }
}
