package com.smartup24.cms.instance.common.xlsx;

import com.smartup24.cms.instance.common.xlsx.XlsxGuard.Limit;
import com.smartup24.cms.instance.common.xlsx.XlsxGuard.Rejected;
import org.jspecify.annotations.Nullable;

/**
 * The row and column numbers of one worksheet as its elements stream by (plan 10/10, item 7.6). A reader sizes a row by
 * the column its last cell names, so a single cell at a far column could cost it the memory of a whole sheet: both
 * numbers are bounded. A {@code row} or {@code c} without a reference takes the next number, as Excel reads it.
 */
final class SheetBounds {

    private final XlsxLimits limits;
    private long row;
    private long column;

    SheetBounds(XlsxLimits limits) {
        this.limits = limits;
    }

    /** One start element of the sheet with its {@code r} attribute, if any. */
    void element(String name, @Nullable String reference) {
        if ("row".equals(name)) {
            row = reference == null ? row + 1 : number(reference, Limit.ROWS);
            column = 0;
            if (row > limits.maxRows()) {
                throw new Rejected(Limit.ROWS, "row " + row + ", at most " + limits.maxRows());
            }
        } else if ("c".equals(name)) {
            column = reference == null ? column + 1 : column(reference);
            if (column > limits.maxColumns()) {
                throw new Rejected(Limit.COLUMNS, "column " + column + ", at most " + limits.maxColumns());
            }
        }
    }

    /** The column number of a cell reference such as {@code AB12}; letters past the bound stop the count early. */
    private long column(String reference) {
        long number = 0;
        for (int i = 0; i < reference.length(); i++) {
            char letter = Character.toUpperCase(reference.charAt(i));
            if (letter < 'A' || letter > 'Z') break;
            number = number * 26 + (letter - 'A' + 1);
            if (number > limits.maxColumns()) {
                throw new Rejected(Limit.COLUMNS, "cell " + abbreviated(reference) + " is past the last column");
            }
        }
        if (number == 0) throw new Rejected(Limit.MALFORMED_XML, "cell reference " + abbreviated(reference));
        return number;
    }

    private long number(String text, Limit limit) {
        String digits = text.strip();
        if (digits.isEmpty() || digits.length() > 10 || !digits.chars().allMatch(Character::isDigit)) {
            throw new Rejected(Limit.MALFORMED_XML, limit + " reference " + abbreviated(text));
        }
        return Long.parseLong(digits);
    }

    private static String abbreviated(String text) {
        return text.length() <= 16 ? text : text.substring(0, 16) + "...";
    }
}
