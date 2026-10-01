package com.smartup24.cms.instance.upl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Builds synthetic xlsx files right in memory: the repository has no binary files.
 * Cell values are only the word {@code TEST} and numbers.
 */
public final class UplXlsxFixtures {

    private UplXlsxFixtures() {}

    /**
     * A file sheet: its name, the header row number (as in Excel, from 1), the header texts and the data rows
     * right under the header. In a data row a {@code String} is text, a {@code Number} is a number,
     * and {@code null} is no cell.
     */
    public record SheetSpec(String name, int headerRow, List<String> header, List<List<Object>> rows) {}

    /** Builds a workbook from the sheets and returns it as bytes. */
    public static byte[] workbook(SheetSpec... sheets) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Workbook book = new Workbook(bytes, "TEST", "1.0")) {
            for (SheetSpec spec : sheets) {
                Worksheet sheet = book.newWorksheet(spec.name());
                writeRow(sheet, spec.headerRow() - 1, spec.header());
                int rowIndex = spec.headerRow();
                for (List<Object> row : spec.rows()) {
                    writeRow(sheet, rowIndex, row);
                    rowIndex++;
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Не удалось собрать тестовый xlsx", failure);
        }
        return bytes.toByteArray();
    }

    /** A file that is not an Excel workbook. */
    public static byte[] notExcel() {
        return "TEST not excel".getBytes(StandardCharsets.UTF_8);
    }

    private static void writeRow(Worksheet sheet, int rowIndex, List<?> values) {
        for (int column = 0; column < values.size(); column++) {
            Object value = values.get(column);
            if (value == null) {
                continue;
            }
            if (value instanceof String text) {
                sheet.value(rowIndex, column, text);
            } else if (value instanceof Number number) {
                sheet.value(rowIndex, column, number);
            } else {
                throw new IllegalArgumentException("Тестовая ячейка неподдерживаемого типа: "
                        + value.getClass().getName());
            }
        }
    }
}
