package com.smartup24.cms.instance.upl.parse;

import com.smartup24.cms.instance.upl.UplLimits;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.parse.UplCells.CellValue;
import com.smartup24.cms.instance.upl.parse.UplParseResult.ErrorRecord;
import com.smartup24.cms.instance.upl.parse.UplStructureMatcher.SheetMatch;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.ExcelReaderException;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Разбор xlsx-файла пакета по опубликованной анкете. Читает файл потоком за два прохода:
 * сначала структура (листы и колонки), потом значения. В базу и в журнал не пишет ничего —
 * итог возвращается в памяти, а строки данных уходят в переданный приёмник по одной.
 *
 * <p>A workbook given as a stream is copied into memory whole by the reader (it needs random access to the zip); the
 * jobs give a file on disk instead ({@link #parse(Path, FormatVersion, Consumer)}), so a 50 MB file costs no heap.
 *
 * <p>This class coordinates the passes; {@link UplStructureMatcher} matches sheets and headers, {@link UplCellChecks}
 * checks values and {@link UplCells} reads cells.
 */
@Component
public class UplXlsxParser {

    /** Файл отклонён: расхождения с анкетой. */
    public static final String UPL_PKG_STRUCTURE = "UPL_PKG_STRUCTURE";
    /** Файл отклонён: не читается как xlsx. */
    public static final String UPL_PKG_UNREADABLE = "UPL_PKG_UNREADABLE";
    /** Файл отклонён: заполненных ячеек больше предела загрузки. */
    public static final String UPL_PKG_TOO_MANY_CELLS = "UPL_PKG_TOO_MANY_CELLS";
    /** Расхождение с анкетой: листа нет в файле. */
    public static final String UPL_STRUCT_SHEET_MISSING = "UPL_STRUCT_SHEET_MISSING";
    /** Расхождение с анкетой: колонки анкеты нет в шапке. */
    public static final String UPL_STRUCT_COLUMN_MISSING = "UPL_STRUCT_COLUMN_MISSING";
    /** Расхождение с анкетой: в шапке колонка, которой нет в анкете. */
    public static final String UPL_STRUCT_COLUMN_UNKNOWN = "UPL_STRUCT_COLUMN_UNKNOWN";
    /** Ошибка ячейки: обязательное значение пусто. */
    public static final String UPL_CELL_REQUIRED = "UPL_CELL_REQUIRED";
    /** Ошибка ячейки: не целое число. */
    public static final String UPL_CELL_NOT_INTEGER = "UPL_CELL_NOT_INTEGER";
    /** Ошибка ячейки: не число. */
    public static final String UPL_CELL_NOT_NUMBER = "UPL_CELL_NOT_NUMBER";
    /** Ошибка ячейки: не дата. */
    public static final String UPL_CELL_NOT_DATE = "UPL_CELL_NOT_DATE";
    /** Ошибка ячейки: ключ объекта учёта не подходит под маску анкеты. */
    public static final String UPL_CELL_KEY_MASK = "UPL_CELL_KEY_MASK";

    private final long maxCells;

    @Autowired
    public UplXlsxParser() {
        this(UplLimits.MAX_CELLS);
    }

    public UplXlsxParser(long maxCells) {
        this.maxCells = maxCells;
    }

    /** Разбирает файл по анкете. Поток закрывает вызывающий. */
    public UplParseResult parse(InputStream content, FormatVersion format) {
        return parse(content, format, row -> {});
    }

    /**
     * Разбирает файл по анкете; строки данных (с ошибками тоже, без пустых и итоговых) отдаёт в {@code rows}
     * в порядке листов анкеты и строк. Поток закрывает вызывающий.
     */
    public UplParseResult parse(InputStream content, FormatVersion format, Consumer<DataRow> rows) {
        try (ReadableWorkbook book = new ReadableWorkbook(content)) {
            return parse(book, format, rows);
        } catch (IOException | ExcelReaderException unreadable) {
            return unreadable();
        }
    }

    /** Разбирает файл на диске по анкете: книга читается с диска, в памяти не копируется. */
    public UplParseResult parse(Path file, FormatVersion format) {
        return parse(file, format, row -> {});
    }

    /**
     * Разбирает файл на диске по анкете; строки данных отдаёт в {@code rows}, как {@link #parse(InputStream,
     * FormatVersion, Consumer)}.
     */
    public UplParseResult parse(Path file, FormatVersion format, Consumer<DataRow> rows) {
        try (ReadableWorkbook book = new ReadableWorkbook(file.toFile())) {
            return parse(book, format, rows);
        } catch (IOException | ExcelReaderException unreadable) {
            return unreadable();
        }
    }

    private UplParseResult parse(ReadableWorkbook book, FormatVersion format, Consumer<DataRow> rows)
            throws IOException {
        List<ErrorRecord> structure = new ArrayList<>();
        List<SheetMatch> sheets = UplStructureMatcher.matchStructure(book, format, structure);
        if (!structure.isEmpty()) {
            return UplParseResult.rejected(
                    UPL_PKG_STRUCTURE, Map.of("count", structure.size()), structure.size(), stored(structure));
        }
        return readValues(sheets, rows);
    }

    private static UplParseResult unreadable() {
        return UplParseResult.rejected(UPL_PKG_UNREADABLE, Map.of(), 0, List.of());
    }

    // --- проход 2: значения ---

    private UplParseResult readValues(List<SheetMatch> sheets, Consumer<DataRow> rows) throws IOException {
        long cells = 0;
        int total = 0;
        int rejected = 0;
        int errorsTotal = 0;
        List<ErrorRecord> errors = new ArrayList<>();
        for (SheetMatch sheet : sheets) {
            try (Stream<Row> fileRows = sheet.file().openStream()) {
                Iterator<Row> reader = fileRows.iterator();
                while (reader.hasNext()) {
                    Row row = reader.next();
                    cells += UplCells.filledCells(row);
                    if (cells > maxCells) {
                        return UplParseResult.rejected(UPL_PKG_TOO_MANY_CELLS, Map.of("limit", maxCells), 0, List.of());
                    }
                    if (row.getRowNum() <= sheet.spec().headerRow()) {
                        continue;
                    }
                    List<CellValue> values = UplCells.rowValues(row, sheet.columns());
                    if (skipped(values, sheet.spec().totalRowMarker())) {
                        continue;
                    }
                    total++;
                    rows.accept(dataRow(sheet, row.getRowNum(), values));
                    List<ErrorRecord> rowErrors =
                            UplCellChecks.checkRow(sheet.spec().sheetName(), sheet.columns(), row.getRowNum(), values);
                    if (!rowErrors.isEmpty()) {
                        rejected++;
                        errorsTotal += rowErrors.size();
                        appendStored(errors, rowErrors);
                    }
                }
            }
        }
        return UplParseResult.verified(total, rejected, errorsTotal, errors);
    }

    /** Пустая строка и итоговая строка не считаются строками данных и ошибок не дают. */
    private static boolean skipped(List<CellValue> values, String totalRowMarker) {
        boolean empty = true;
        boolean total = false;
        String marker = totalRowMarker == null ? null : UplCells.normalized(totalRowMarker);
        for (CellValue value : values) {
            if (value.text() == null) {
                continue;
            }
            empty = false;
            if (marker != null
                    && !marker.isEmpty()
                    && UplCells.normalized(value.text()).startsWith(marker)) {
                total = true;
            }
        }
        return empty || total;
    }

    private static DataRow dataRow(SheetMatch sheet, int rowNo, List<CellValue> values) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < sheet.columns().size(); index++) {
            fields.put(
                    sheet.columns().get(index).column().targetField(),
                    values.get(index).text());
        }
        return new DataRow(sheet.spec().sheetName(), rowNo, fields);
    }

    private static void appendStored(List<ErrorRecord> stored, List<ErrorRecord> found) {
        for (ErrorRecord error : found) {
            if (stored.size() >= UplLimits.MAX_STORED_ERRORS) {
                return;
            }
            stored.add(error);
        }
    }

    private static List<ErrorRecord> stored(List<ErrorRecord> found) {
        return found.size() <= UplLimits.MAX_STORED_ERRORS
                ? List.copyOf(found)
                : List.copyOf(found.subList(0, UplLimits.MAX_STORED_ERRORS));
    }

    /** Строка данных как в файле: лист, № строки Excel и значения по полям анкеты ({@code null} — пустая ячейка). */
    public record DataRow(String sheet, int sourceRowNo, Map<String, Object> fields) {}
}
