package com.smartup24.cms.instance.report.imports;

import com.smartup24.cms.instance.common.entity.importing.EntityImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ExcelReaderException;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.jspecify.annotations.Nullable;

/**
 * A filled import file on disk (ADR-0032, 10.1), read as a stream of its first sheet — the reader keeps the zip on disk
 * and holds one row at a time, as the uploads do (plan 10/10, item 3.9): the field keys of its hidden second row, the
 * number of its data rows, and the data rows themselves, each with its filled cells by key as a text, a number or a
 * flag. A row without a filled cell is no data row.
 */
final class ImportFile implements AutoCloseable {

    /** A file that cannot be read as xlsx, or whose layout is not the template's. */
    static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreadable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ReadableWorkbook book;

    private ImportFile(ReadableWorkbook book) {
        this.book = book;
    }

    static ImportFile open(Path path) {
        try {
            return new ImportFile(new ReadableWorkbook(path.toFile()));
        } catch (IOException | ExcelReaderException unreadable) {
            throw new Unreadable("The import file is not an xlsx workbook", unreadable);
        }
    }

    /** The keys of the second row by column, null for an empty cell; empty when the sheet has no second row. */
    List<@Nullable String> keys() {
        try (Stream<Row> rows = book.getFirstSheet().openStream()) {
            Optional<Row> keyRow =
                    rows.filter(row -> row.getRowNum() == ImportSheets.KEY_ROW).findFirst();
            List<@Nullable String> keys = new ArrayList<>();
            keyRow.ifPresent(row -> {
                for (int c = 0; c < row.getCellCount(); c++) {
                    Object raw = value(row.getCell(c));
                    keys.add(raw == null ? null : String.valueOf(raw).strip());
                }
            });
            return keys;
        } catch (IOException | ExcelReaderException unreadable) {
            throw new Unreadable("The key row of the import file cannot be read", unreadable);
        }
    }

    /** How many data rows the file has, counting up to {@code limit + 1}: more than the limit is refused. */
    int count(List<@Nullable String> keys, int limit) {
        int[] count = {0};
        rows(keys, 0, row -> count[0]++, limit + 1);
        return count[0];
    }

    /**
     * Passes the data rows after the first {@code skip} to {@code rows}, in file order, at most {@code most} of them in
     * all (the skipped included).
     */
    void rows(List<@Nullable String> keys, int skip, Consumer<EntityImporter.Row> rows, int most) {
        int seen = 0;
        try (Stream<Row> stream = book.getFirstSheet().openStream()) {
            Iterator<Row> reader = stream.iterator();
            while (reader.hasNext() && seen < most) {
                Row row = reader.next();
                if (row.getRowNum() < ImportSheets.FIRST_DATA_ROW) continue;
                Map<String, Object> cells = cells(row, keys);
                if (cells.isEmpty()) continue;
                seen++;
                if (seen > skip) rows.accept(new EntityImporter.Row(row.getRowNum(), cells));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ExcelReaderException unreadable) {
            throw new Unreadable("A row of the import file cannot be read", unreadable);
        }
    }

    /** The filled cells of a row by the key of their column; a cell under no key is left out. */
    static Map<String, Object> cells(Row row, List<@Nullable String> keys) {
        Map<String, Object> cells = new LinkedHashMap<>();
        int width = Math.min(row.getCellCount(), keys.size());
        for (int c = 0; c < width; c++) {
            String key = keys.get(c);
            Object raw = value(row.getCell(c));
            if (key != null && !key.isEmpty() && raw != null) cells.put(key, raw);
        }
        return cells;
    }

    /** A cell as a text, a number or a flag; null when it is empty or holds only blanks. */
    static @Nullable Object value(@Nullable Cell cell) {
        if (cell == null) return null;
        return switch (cell.getType()) {
            case NUMBER -> cell.asNumber();
            case BOOLEAN -> cell.asBoolean();
            case EMPTY -> null;
            case STRING -> text(cell.asString());
            default -> number(text(cell.getRawValue()));
        };
    }

    /** A formula's or an error's cached value: a number when it reads as one, otherwise its text. */
    private static @Nullable Object number(@Nullable String text) {
        if (text == null) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException notNumber) {
            return text;
        }
    }

    private static @Nullable String text(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public void close() throws IOException {
        book.close();
    }
}
