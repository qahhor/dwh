package com.smartup24.cms.instance.upl.parse;

import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.parse.UplCells.CellValue;
import com.smartup24.cms.instance.upl.parse.UplParseResult.ErrorRecord;
import com.smartup24.cms.instance.upl.parse.UplStructureMatcher.ColumnMatch;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The value checks of {@link UplXlsxParser}: required cells, integers, numbers, dates in the accepted formats and
 * object keys against the format's mask. One cell gives at most one error.
 */
final class UplCellChecks {

    private static final Pattern INTEGER_TEXT = Pattern.compile("-?\\d+");
    private static final Pattern NUMBER_TEXT = Pattern.compile("-?\\d+([.,]\\d+)?");
    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DOTTED_DATE =
            DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);

    private UplCellChecks() {}

    /** The errors of one data row: one record per cell that fails its column's check. */
    static List<ErrorRecord> checkRow(String sheetName, List<ColumnMatch> columns, int rowNo, List<CellValue> values) {
        List<ErrorRecord> errors = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index).column();
            CellValue value = values.get(index);
            String code = check(column, value);
            if (code != null) {
                errors.add(new ErrorRecord(
                        sheetName, rowNo, column.nameInFile(), UplCells.shortened(value.text()), code, Map.of()));
            }
        }
        return errors;
    }

    /** The error code of one cell, at most one per cell; {@code null} — no error. */
    static String check(Column column, CellValue value) {
        if (value.text() == null) {
            return column.required() ? UplXlsxParser.UPL_CELL_REQUIRED : null;
        }
        return switch (column.dataType()) {
            case TEXT, REF_CODE -> null;
            case INTEGER -> checkInteger(value);
            case NUMBER -> checkNumber(value);
            case DATE -> checkDate(value);
            case OBJECT_KEY -> checkKey(column, value);
        };
    }

    private static String checkInteger(CellValue value) {
        BigDecimal number = numberOf(value);
        if (number != null) {
            return number.stripTrailingZeros().scale() <= 0 ? null : UplXlsxParser.UPL_CELL_NOT_INTEGER;
        }
        return INTEGER_TEXT.matcher(value.text().strip()).matches() ? null : UplXlsxParser.UPL_CELL_NOT_INTEGER;
    }

    private static String checkNumber(CellValue value) {
        if (numberOf(value) != null) {
            return null;
        }
        return NUMBER_TEXT.matcher(value.text().strip()).matches() ? null : UplXlsxParser.UPL_CELL_NOT_NUMBER;
    }

    private static String checkDate(CellValue value) {
        if (numberOf(value) != null) {
            return null;
        }
        String text = value.text().strip();
        return isDate(text, ISO_DATE) || isDate(text, DOTTED_DATE) ? null : UplXlsxParser.UPL_CELL_NOT_DATE;
    }

    private static boolean isDate(String text, DateTimeFormatter formatter) {
        try {
            LocalDate.parse(text, formatter);
            return true;
        } catch (DateTimeParseException wrongFormat) {
            return false;
        }
    }

    private static String checkKey(Column column, CellValue value) {
        String key = value.text().strip();
        BigDecimal number = numberOf(value);
        if (number != null) {
            if (number.stripTrailingZeros().scale() > 0) {
                return UplXlsxParser.UPL_CELL_KEY_MASK;
            }
            key = number.toBigInteger().toString();
        }
        if (column.keyMask() == null) {
            return null;
        }
        String candidate = padded(key, column.keyPadLength(), column.keyPadMax());
        return Pattern.matches(column.keyMask(), candidate) ? null : UplXlsxParser.UPL_CELL_KEY_MASK;
    }

    /** Left zero padding, only for the comparison with the mask; the key in the file stays as it is. */
    private static String padded(String key, Integer padLength, Integer padMax) {
        if (padLength == null || key.length() >= padLength) {
            return key;
        }
        int missing = padLength - key.length();
        if (padMax != null && missing > padMax) {
            return key;
        }
        return "0".repeat(missing) + key;
    }

    private static BigDecimal numberOf(CellValue value) {
        if (!value.numeric()) {
            return null;
        }
        try {
            return new BigDecimal(value.text().strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
