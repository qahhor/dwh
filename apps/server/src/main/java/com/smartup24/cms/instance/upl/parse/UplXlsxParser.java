package com.smartup24.cms.instance.upl.parse;

import com.smartup24.cms.instance.common.xlsx.XlsxGuard;
import com.smartup24.cms.instance.common.xlsx.XlsxLimits;
import com.smartup24.cms.instance.upl.UplLimits;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.parse.UplCells.CellValue;
import com.smartup24.cms.instance.upl.parse.UplParseResult.ErrorRecord;
import com.smartup24.cms.instance.upl.parse.UplStructureMatcher.SheetMatch;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Parses the xlsx file of a package by the published format. Reads the file as a stream in two passes:
 * first the structure (sheets and columns), then the values. Writes nothing to the database and logs only a refused file:
 * the result is returned in memory, and data rows go to the given consumer one by one.
 *
 * <p>The reader needs random access to the zip: a workbook given as a stream is spooled to a temporary file first, and
 * the jobs give a file on disk ({@link #parse(Path, FormatVersion, Consumer)}), so a 50 MB file costs no heap. Before
 * the reader opens it the file is checked against {@link XlsxLimits} (plan 10/10, item 7.6): a zip bomb, a flood of
 * shared strings or a cell past the last column is refused as unreadable.
 *
 * <p>This class coordinates the passes; {@link UplStructureMatcher} matches sheets and headers, {@link UplCellChecks}
 * checks values and {@link UplCells} reads cells.
 */
@Component
public class UplXlsxParser {

    private static final Logger log = LoggerFactory.getLogger(UplXlsxParser.class);

    /** File rejected: mismatches with the format. */
    public static final String UPL_PKG_STRUCTURE = "UPL_PKG_STRUCTURE";
    /** File rejected: it cannot be read as xlsx. */
    public static final String UPL_PKG_UNREADABLE = "UPL_PKG_UNREADABLE";
    /** File rejected: more filled cells than the upload limit. */
    public static final String UPL_PKG_TOO_MANY_CELLS = "UPL_PKG_TOO_MANY_CELLS";
    /** Mismatch with the format: the sheet is missing from the file. */
    public static final String UPL_STRUCT_SHEET_MISSING = "UPL_STRUCT_SHEET_MISSING";
    /** Mismatch with the format: a format column is missing from the header. */
    public static final String UPL_STRUCT_COLUMN_MISSING = "UPL_STRUCT_COLUMN_MISSING";
    /** Mismatch with the format: the header has a column that the format does not have. */
    public static final String UPL_STRUCT_COLUMN_UNKNOWN = "UPL_STRUCT_COLUMN_UNKNOWN";
    /** Cell error: a required value is empty. */
    public static final String UPL_CELL_REQUIRED = "UPL_CELL_REQUIRED";
    /** Cell error: not an integer. */
    public static final String UPL_CELL_NOT_INTEGER = "UPL_CELL_NOT_INTEGER";
    /** Cell error: not a number. */
    public static final String UPL_CELL_NOT_NUMBER = "UPL_CELL_NOT_NUMBER";
    /** Cell error: not a date. */
    public static final String UPL_CELL_NOT_DATE = "UPL_CELL_NOT_DATE";
    /** Cell error: the accounting object key does not match the format mask. */
    public static final String UPL_CELL_KEY_MASK = "UPL_CELL_KEY_MASK";

    private final long maxCells;
    private final XlsxLimits limits;

    public UplXlsxParser() {
        this(UplLimits.MAX_CELLS);
    }

    public UplXlsxParser(long maxCells) {
        this(maxCells, XlsxLimits.defaults());
    }

    @Autowired
    public UplXlsxParser(XlsxLimits limits) {
        this(UplLimits.MAX_CELLS, limits);
    }

    public UplXlsxParser(long maxCells, XlsxLimits limits) {
        this.maxCells = maxCells;
        this.limits = limits;
    }

    /** Parses the file by the format. The caller closes the stream. */
    public UplParseResult parse(InputStream content, FormatVersion format) {
        return parse(content, format, row -> {});
    }

    /**
     * Parses the file by the format; passes data rows (including rows with errors, without empty and total rows)
     * to {@code rows} in the order of the format sheets and rows. The caller closes the stream.
     */
    public UplParseResult parse(InputStream content, FormatVersion format, Consumer<DataRow> rows) {
        Path spooled = null;
        try {
            spooled = Files.createTempFile("upl-parse-", ".xlsx");
            Files.copy(content, spooled, StandardCopyOption.REPLACE_EXISTING);
            return parse(spooled, format, rows);
        } catch (IOException spoolFailure) {
            throw new UncheckedIOException("The workbook cannot be spooled for parsing", spoolFailure);
        } finally {
            if (spooled != null) deleteQuietly(spooled);
        }
    }

    /** Parses a file on disk by the format: the workbook is read from disk and not copied into memory. */
    public UplParseResult parse(Path file, FormatVersion format) {
        return parse(file, format, row -> {});
    }

    /**
     * Parses a file on disk by the format; passes data rows to {@code rows}, like {@link #parse(InputStream,
     * FormatVersion, Consumer)}.
     */
    public UplParseResult parse(Path file, FormatVersion format, Consumer<DataRow> rows) {
        try {
            XlsxGuard.check(file, limits);
        } catch (XlsxGuard.Rejected outOfBounds) {
            log.warn("upl_xlsx_out_of_bounds limit={}", outOfBounds.limit(), outOfBounds);
            return unreadable();
        } catch (IOException unreadable) {
            log.warn("upl_xlsx_unreadable", unreadable);
            return unreadable();
        }
        try (ReadableWorkbook book = new ReadableWorkbook(file.toFile())) {
            return parse(book, format, rows);
        } catch (IOException | ExcelReaderException unreadable) {
            return unreadable();
        }
    }

    private static void deleteQuietly(Path spooled) {
        try {
            Files.deleteIfExists(spooled);
        } catch (IOException leftBehind) {
            log.warn("upl_parse_spool_not_deleted path={}", spooled, leftBehind);
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

    // --- pass 2: values ---

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

    /** An empty row and a total row are not data rows and produce no errors. */
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

    /** A data row as in the file: sheet, Excel row number and values by format field ({@code null} = empty cell). */
    public record DataRow(String sheet, int sourceRowNo, Map<String, Object> fields) {}
}
