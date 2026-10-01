package com.smartup24.cms.instance.upl.parse;

import com.smartup24.cms.instance.upl.UplLimits;
import com.smartup24.cms.instance.upl.parse.UplStructureMatcher.ColumnMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.Row;

/** Reading and normalizing cell values of a workbook row for {@link UplXlsxParser}. */
final class UplCells {

    private static final CellValue EMPTY_CELL = new CellValue(null, false);

    private UplCells() {}

    /** A cell value: its text as in the file ({@code null} — empty) and whether the cell is numeric. */
    record CellValue(String text, boolean numeric) {}

    /** The value of the cell at the index; a missing, empty or blank cell reads as empty. */
    static CellValue cellValue(Row row, int index) {
        if (index < 0 || index >= row.getCellCount()) {
            return EMPTY_CELL;
        }
        Cell cell = row.getCell(index);
        if (cell == null || cell.getType() == CellType.EMPTY) {
            return EMPTY_CELL;
        }
        String text = cell.getType() == CellType.STRING ? cell.getText() : cell.getRawValue();
        if (text == null || text.isBlank()) {
            return EMPTY_CELL;
        }
        return new CellValue(text, cell.getType() == CellType.NUMBER);
    }

    /** The values of the row for the matched columns, in their order. */
    static List<CellValue> rowValues(Row row, List<ColumnMatch> columns) {
        List<CellValue> values = new ArrayList<>(columns.size());
        for (ColumnMatch column : columns) {
            values.add(cellValue(row, column.index()));
        }
        return values;
    }

    /** The number of cells of the row that hold a value. */
    static int filledCells(Row row) {
        int filled = 0;
        for (int index = 0; index < row.getCellCount(); index++) {
            if (cellValue(row, index).text() != null) {
                filled++;
            }
        }
        return filled;
    }

    /** A cell value cut to the length kept in an error record. */
    static String shortened(String value) {
        if (value == null || value.length() <= UplLimits.MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, UplLimits.MAX_VALUE_LENGTH);
    }

    /** Headers compare without case, outer spaces and runs of inner spaces: "Total,  USD" is "total, usd". */
    static String normalized(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
