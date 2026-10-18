package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.jspecify.annotations.Nullable;

/**
 * One export as an xlsx sheet (ADR-0018): a header row of the list's column
 * titles, then a row per item. Values keep their kind — numbers as numbers,
 * dates and moments as Excel dates in UTC, flags as yes/no, choices by their
 * words, money in its currency's format ({@link ExportFormats}) — so the file
 * can be sorted and summed at once. The rows of a document — the lines of an order (ADR-0043) — go to a sheet of
 * their own per collection: each row names its record by the record's first column, its place, then its values.
 */
final class ExportWorkbookWriter implements AutoCloseable {

    /** Who wrote the file, in its properties; the format wants the version as XX.YYYY. */
    static final String APPLICATION = "SmartupCMS";

    static final String APP_VERSION = "1.0";
    private static final String HEADER_FILL = "DCE6F2";
    private static final String DATE_FORMAT = "dd.mm.yyyy";
    private static final String MOMENT_FORMAT = "dd.mm.yyyy hh:mm";

    /** A sheet of the rows of a collection and the next row to write on it. */
    private static final class DetailSheet {
        private final QueryListExporter.Detail detail;
        private final Worksheet sheet;
        private int row = 1;

        private DetailSheet(QueryListExporter.Detail detail, Worksheet sheet) {
            this.detail = detail;
            this.sheet = sheet;
        }
    }

    private final Workbook workbook;
    private final Worksheet sheet;
    private final List<QueryField> fields;
    private final Function<String, String> text;
    private final List<DetailSheet> details = new ArrayList<>();
    private int row;

    ExportWorkbookWriter(
            OutputStream out,
            String sheetName,
            List<QueryField> fields,
            Function<String, String> text,
            String application,
            String version) {
        this(out, sheetName, fields, List.of(), text, application, version);
    }

    ExportWorkbookWriter(
            OutputStream out,
            String sheetName,
            List<QueryField> fields,
            List<QueryListExporter.Detail> details,
            Function<String, String> text,
            String application,
            String version) {
        this.workbook = new Workbook(out, application, version);
        this.sheet = workbook.newWorksheet(sheetName(sheetName));
        this.fields = List.copyOf(fields);
        this.text = text;
        header(sheet, 0, this.fields);
        row = 1;
        for (QueryListExporter.Detail detail : details) {
            Worksheet rows = workbook.newWorksheet(sheetName(text.apply(detail.labelKey())));
            rows.value(0, 0, text.apply("export.detail.record"));
            rows.value(0, 1, text.apply("export.detail.position"));
            for (int c = 0; c < 2; c++)
                rows.style(0, c).bold().fillColor(HEADER_FILL).set();
            rows.width(0, 28);
            header(rows, 2, detail.fields());
            this.details.add(new DetailSheet(detail, rows));
        }
    }

    private void header(Worksheet target, int offset, List<QueryField> columns) {
        for (int c = 0; c < columns.size(); c++) {
            QueryField field = columns.get(c);
            target.value(0, offset + c, field.label() != null ? field.label() : text.apply(field.labelKey()));
            target.style(0, offset + c).bold().fillColor(HEADER_FILL).set();
            target.width(offset + c, width(field));
        }
        target.freezePane(0, 1);
    }

    /**
     * One item, read by field key (the item's JSON property names); a custom field from its attributes; the rows of
     * its collections on their sheets.
     */
    void add(Map<String, Object> item) {
        Map<?, ?> labels = item.get("labels") instanceof Map<?, ?> named ? named : Map.of();
        cells(sheet, row, 0, fields, item, labels);
        row++;
        for (DetailSheet detail : details) rows(detail, item, labels);
    }

    /** The rows of one collection of the item, each after its record's name and its place. */
    private void rows(DetailSheet detail, Map<String, Object> item, Map<?, ?> labels) {
        if (!(item.get(detail.detail.key()) instanceof List<?> lines)) return;
        List<?> named = labels.get(detail.detail.key()) instanceof List<?> list ? list : List.of();
        String record = recordName(item, labels);
        for (int index = 0; index < lines.size(); index++) {
            if (!(lines.get(index) instanceof Map<?, ?> line)) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            line.forEach((key, value) -> values.put(String.valueOf(key), value));
            Map<?, ?> lineLabels = index < named.size() && named.get(index) instanceof Map<?, ?> map ? map : Map.of();
            detail.sheet.value(detail.row, 0, record);
            detail.sheet.value(detail.row, 1, index + 1);
            cells(detail.sheet, detail.row, 2, detail.detail.fields(), values, lineLabels);
            detail.row++;
        }
    }

    /** What names a record on the sheets of its rows: its first column as written, else its id. */
    private String recordName(Map<String, Object> item, Map<?, ?> labels) {
        if (!fields.isEmpty()) {
            String named = referenceName(labels, fields.getFirst());
            if (named != null) return named;
            Object value = ExportFormats.value(fields.getFirst(), item);
            if (value != null) return String.valueOf(value);
        }
        return String.valueOf(item.get("id"));
    }

    private void cells(
            Worksheet target,
            int at,
            int offset,
            List<QueryField> columns,
            Map<String, Object> item,
            Map<?, ?> labels) {
        for (int index = 0; index < columns.size(); index++) {
            QueryField field = columns.get(index);
            int c = offset + index;
            Object value =
                    field.attribute() != null ? attribute(item, field.attribute()) : ExportFormats.value(field, item);
            if (value == null) continue;
            if (field.attribute() != null) {
                // Written by people before validation existed: shape is not guaranteed, so never fail the file on it.
                customValue(target, at, c, field, value);
                continue;
            }
            String named = referenceName(labels, field);
            if (named != null) {
                target.value(at, c, named);
                continue;
            }
            value(target, at, c, field, value);
        }
    }

    private void value(Worksheet sheet, int row, int c, QueryField field, Object value) {
        if (ExportFormats.write(sheet, row, c, field, value)) return;
        switch (field.type()) {
            case NUMBER -> {
                if (value instanceof Number number) sheet.value(row, c, number);
                else sheet.value(row, c, String.valueOf(value));
            }
            case DATE -> {
                sheet.value(row, c, LocalDate.parse(String.valueOf(value)));
                sheet.style(row, c).format(DATE_FORMAT).set();
            }
            case INSTANT -> moment(sheet, row, c, String.valueOf(value));
            case TIME -> sheet.value(row, c, String.valueOf(value));
            case BOOLEAN -> sheet.value(row, c, text.apply(Boolean.TRUE.equals(value) ? "common.yes" : "common.no"));
            case ENUM -> sheet.value(row, c, enumWords(field, String.valueOf(value)));
            default -> sheet.value(row, c, String.valueOf(value));
        }
    }

    /**
     * The name of the row a reference names, resolved in the exporter's scope ({@code labels} of the item): a file is
     * read by people, not by keys (ADR-0041). Null when the item has no name for it, and the key is written.
     */
    private static @Nullable String referenceName(Map<?, ?> labels, QueryField field) {
        if (field.ref() == null) return null;
        Object named = labels.get(field.key());
        if (named instanceof Collection<?> names && !names.isEmpty())
            return names.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return named instanceof String name && !name.isBlank() ? name : null;
    }

    private static Object attribute(Map<String, Object> item, String code) {
        return item.get("attributes") instanceof Map<?, ?> attributes ? attributes.get(code) : null;
    }

    private void customValue(Worksheet sheet, int row, int c, QueryField field, Object value) {
        String raw = String.valueOf(value);
        switch (field.type()) {
            case NUMBER -> {
                if (value instanceof Number number) sheet.value(row, c, number);
                else sheet.value(row, c, raw);
            }
            case BOOLEAN ->
                sheet.value(
                        row,
                        c,
                        "true".equalsIgnoreCase(raw)
                                ? text.apply("common.yes")
                                : "false".equalsIgnoreCase(raw) ? text.apply("common.no") : raw);
            case DATE -> {
                try {
                    sheet.value(row, c, LocalDate.parse(raw.length() >= 10 ? raw.substring(0, 10) : raw));
                    sheet.style(row, c).format(DATE_FORMAT).set();
                } catch (DateTimeParseException e) {
                    sheet.value(row, c, raw);
                }
            }
            case INSTANT -> {
                try {
                    moment(sheet, row, c, raw);
                } catch (DateTimeParseException e) {
                    sheet.value(row, c, raw);
                }
            }
            default -> sheet.value(row, c, raw);
        }
    }

    /** A moment ({@code 2026-10-01T09:30:00Z} or with an offset) as a date and time of the sheet, in UTC. */
    private static void moment(Worksheet sheet, int row, int c, String text) {
        sheet.value(
                row,
                c,
                OffsetDateTime.parse(text).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        sheet.style(row, c).format(MOMENT_FORMAT).set();
    }

    int rows() {
        return row - 1;
    }

    @Override
    public void close() throws IOException {
        if (row > 1) {
            sheet.setAutoFilter(0, 0, fields.size() - 1);
        }
        for (DetailSheet detail : details) {
            if (detail.row > 1)
                detail.sheet.setAutoFilter(0, 0, detail.detail.fields().size() + 1);
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
            case DATE, TIME -> 14;
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
