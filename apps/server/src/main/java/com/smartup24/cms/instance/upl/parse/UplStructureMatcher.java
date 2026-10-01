package com.smartup24.cms.instance.upl.parse;

import static com.smartup24.cms.instance.upl.parse.UplCells.normalized;

import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.format.UplFormatModel.MatchBy;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.parse.UplParseResult.ErrorRecord;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;

/**
 * The first pass of {@link UplXlsxParser}: finds the sheets of the format in the workbook and matches the header of
 * each to the format's columns, by header text or by position. Every mismatch becomes a structure error.
 */
final class UplStructureMatcher {

    private UplStructureMatcher() {}

    /** A matched sheet: its description in the format, the sheet of the file and the columns with their indexes. */
    record SheetMatch(Sheet spec, org.dhatim.fastexcel.reader.Sheet file, List<ColumnMatch> columns) {}

    /** A column of the format and its index in the file (from 0). */
    record ColumnMatch(Column column, int index) {}

    /** The matched sheets in the format's order; the mismatches are added to {@code errors}. */
    static List<SheetMatch> matchStructure(ReadableWorkbook book, FormatVersion format, List<ErrorRecord> errors)
            throws IOException {
        List<org.dhatim.fastexcel.reader.Sheet> inFile = book.getSheets().toList();
        List<SheetMatch> matched = new ArrayList<>();
        for (Sheet spec : ordered(format.sheets(), Sheet::ordinal)) {
            Optional<org.dhatim.fastexcel.reader.Sheet> found = findSheet(inFile, spec.sheetName());
            if (found.isEmpty()) {
                errors.add(new ErrorRecord(
                        spec.sheetName(),
                        null,
                        null,
                        null,
                        UplXlsxParser.UPL_STRUCT_SHEET_MISSING,
                        Map.of("sheet", spec.sheetName())));
                continue;
            }
            List<String> header = headerTexts(found.get(), spec.headerRow());
            matched.add(new SheetMatch(spec, found.get(), matchColumns(spec, header, matchBy(format), errors)));
        }
        return matched;
    }

    private static Optional<org.dhatim.fastexcel.reader.Sheet> findSheet(
            List<org.dhatim.fastexcel.reader.Sheet> inFile, String name) {
        for (org.dhatim.fastexcel.reader.Sheet sheet : inFile) {
            if (sheet.getName().equals(name)) {
                return Optional.of(sheet);
            }
        }
        String wanted = normalized(name);
        for (org.dhatim.fastexcel.reader.Sheet sheet : inFile) {
            if (normalized(sheet.getName()).equals(wanted)) {
                return Optional.of(sheet);
            }
        }
        return Optional.empty();
    }

    /** The header cell texts after {@code strip}; {@code null} — an empty cell. No such row — an empty header. */
    private static List<String> headerTexts(org.dhatim.fastexcel.reader.Sheet file, int headerRow) throws IOException {
        try (Stream<Row> rows = file.openStream()) {
            Optional<Row> header =
                    rows.filter(row -> row.getRowNum() == headerRow).findFirst();
            if (header.isEmpty()) {
                return List.of();
            }
            Row row = header.get();
            List<String> texts = new ArrayList<>();
            for (int index = 0; index < row.getCellCount(); index++) {
                String text = UplCells.cellValue(row, index).text();
                texts.add(text == null ? null : text.strip());
            }
            return texts;
        }
    }

    private static List<ColumnMatch> matchColumns(
            Sheet spec, List<String> header, MatchBy matchBy, List<ErrorRecord> errors) {
        return matchBy == MatchBy.POSITION
                ? matchByPosition(spec, header, errors)
                : matchByHeader(spec, header, errors);
    }

    private static List<ColumnMatch> matchByHeader(Sheet spec, List<String> header, List<ErrorRecord> errors) {
        List<ColumnMatch> matched = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (Column column : ordered(spec.columns(), Column::ordinal)) {
            column.acceptedHeaders().forEach(name -> known.add(normalized(name)));
            int index = indexOfHeader(header, column.acceptedHeaders());
            if (index < 0) {
                errors.add(structureError(spec, column.nameInFile(), UplXlsxParser.UPL_STRUCT_COLUMN_MISSING));
                continue;
            }
            matched.add(new ColumnMatch(column, index));
        }
        for (String text : header) {
            if (text != null && !known.contains(normalized(text))) {
                errors.add(structureError(spec, text, UplXlsxParser.UPL_STRUCT_COLUMN_UNKNOWN));
            }
        }
        return matched;
    }

    private static List<ColumnMatch> matchByPosition(Sheet spec, List<String> header, List<ErrorRecord> errors) {
        List<ColumnMatch> matched = new ArrayList<>();
        Set<Integer> described = new HashSet<>();
        for (Column column : ordered(spec.columns(), Column::ordinal)) {
            int index = column.filePosition() == null ? -1 : column.filePosition() - 1;
            described.add(index);
            if (index < 0 || index >= header.size() || header.get(index) == null) {
                errors.add(structureError(spec, column.nameInFile(), UplXlsxParser.UPL_STRUCT_COLUMN_MISSING));
                continue;
            }
            matched.add(new ColumnMatch(column, index));
        }
        for (int index = 0; index < header.size(); index++) {
            if (header.get(index) != null && !described.contains(index)) {
                errors.add(structureError(spec, header.get(index), UplXlsxParser.UPL_STRUCT_COLUMN_UNKNOWN));
            }
        }
        return matched;
    }

    /** The first header cell that carries the column's name or one of its synonyms. */
    private static int indexOfHeader(List<String> header, List<String> accepted) {
        Set<String> wanted = new HashSet<>();
        accepted.forEach(name -> wanted.add(normalized(name)));
        for (int index = 0; index < header.size(); index++) {
            if (header.get(index) != null && wanted.contains(normalized(header.get(index)))) {
                return index;
            }
        }
        return -1;
    }

    private static ErrorRecord structureError(Sheet spec, String column, String code) {
        return new ErrorRecord(
                spec.sheetName(),
                null,
                column,
                null,
                code,
                Map.of("sheet", spec.sheetName(), "column", column, "headerRow", spec.headerRow()));
    }

    private static MatchBy matchBy(FormatVersion format) {
        return format.matchColumnsBy() == null ? MatchBy.HEADER : format.matchColumnsBy();
    }

    private static <T> List<T> ordered(List<T> items, ToIntFunction<T> ordinal) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(ordinal));
        return sorted;
    }
}
