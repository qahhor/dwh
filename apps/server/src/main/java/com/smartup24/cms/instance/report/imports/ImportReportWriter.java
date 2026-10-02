package com.smartup24.cms.instance.report.imports;

import com.smartup24.cms.instance.report.repository.ReportImportRepository.ErrorRow;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ExcelReaderException;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;

/**
 * The report of an import (ADR-0032, 10.1): the file the person sent, row for row under the same titles and keys, with
 * one more column that lists the problems of each refused row in the import's language. The column has no key, so the
 * file, once corrected, can be imported again as it is. The problems are read in slices as the rows go by.
 */
final class ImportReportWriter {

    /** How many file rows one read of the stored problems covers. */
    static final int SLICE = 1000;

    /** Reads the stored problems of the file rows from the first to the last number given, by row. */
    @FunctionalInterface
    interface Problems {
        List<ErrorRow> between(int fromRow, int toRow);
    }

    private ImportReportWriter() {}

    /** Writes the report of {@code source} to {@code out}; the caller closes it. */
    static void write(Path source, OutputStream out, String errorsTitle, Problems problems) throws IOException {
        Workbook workbook = new Workbook(out, ImportSheets.APPLICATION, ImportSheets.APP_VERSION);
        Worksheet sheet = workbook.newWorksheet("report");
        try (ReadableWorkbook book = new ReadableWorkbook(source.toFile());
                Stream<Row> rows = book.getFirstSheet().openStream()) {
            Iterator<Row> reader = rows.iterator();
            List<String> titles = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            Map<Integer, String> slice = Map.of();
            int sliceEnd = 0;
            int written = ImportSheets.FIRST_DATA_ROW - 1;
            while (reader.hasNext()) {
                Row row = reader.next();
                if (row.getRowNum() == ImportSheets.TITLE_ROW) titles = texts(row);
                if (row.getRowNum() == ImportSheets.KEY_ROW) keys = texts(row);
                if (row.getRowNum() < ImportSheets.FIRST_DATA_ROW) continue;
                if (row.getRowNum() > sliceEnd) {
                    sliceEnd = row.getRowNum() + SLICE - 1;
                    slice = joined(problems.between(row.getRowNum(), sliceEnd));
                }
                boolean filled = copy(sheet, written, row, keys.size());
                String message = slice.get(row.getRowNum());
                if (message != null) sheet.value(written, keys.size(), message);
                if (filled || message != null) written++;
            }
            for (int c = 0; c < keys.size(); c++) {
                ImportSheets.header(sheet, c, c < titles.size() ? titles.get(c) : keys.get(c), keys.get(c));
            }
            sheet.value(ImportSheets.TITLE_ROW - 1, keys.size(), errorsTitle);
            sheet.style(ImportSheets.TITLE_ROW - 1, keys.size()).bold().set();
            sheet.width(keys.size(), 60);
            ImportSheets.finishHeader(sheet);
        } catch (ExcelReaderException unreadable) {
            throw new UncheckedIOException(new IOException("The import file cannot be read again", unreadable));
        }
        workbook.finish();
    }

    /** Copies the cells of a data row under the keyed columns; false when none is filled. */
    private static boolean copy(Worksheet sheet, int out, Row row, int width) {
        boolean filled = false;
        for (int c = 0; c < Math.min(width, row.getCellCount()); c++) {
            Object value = ImportFile.value(row.getCell(c));
            if (value == null) continue;
            filled = true;
            switch (value) {
                case BigDecimal number -> sheet.value(out, c, number);
                case Boolean flag -> sheet.value(out, c, flag);
                default -> sheet.value(out, c, String.valueOf(value));
            }
        }
        return filled;
    }

    private static List<String> texts(Row row) {
        List<String> texts = new ArrayList<>();
        for (int c = 0; c < row.getCellCount(); c++) {
            Object value = ImportFile.value(row.getCell(c));
            texts.add(value == null ? "" : String.valueOf(value));
        }
        return texts;
    }

    /** The problems of each row as one text: the field and the message of each, one per line. */
    private static Map<Integer, String> joined(List<ErrorRow> problems) {
        return problems.stream()
                .collect(Collectors.groupingBy(
                        ErrorRow::rowNo,
                        LinkedHashMap::new,
                        Collectors.mapping(ImportReportWriter::line, Collectors.joining("\n"))));
    }

    private static String line(ErrorRow problem) {
        int dot = problem.field().indexOf("].");
        String field = dot < 0 ? "" : problem.field().substring(dot + 2) + ": ";
        return field + problem.message();
    }
}
