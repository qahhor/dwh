package com.smartup24.cms.instance.report.imports;

import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Column;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.dhatim.fastexcel.Worksheet;

/**
 * The layout every import file shares (ADR-0032, 10.1): the first row names the columns in the reader's language, the
 * second — hidden — by the keys of their fields, and the data starts on the third. The template, the reading of a
 * filled file and the report keep to it.
 */
final class ImportSheets {

    /** The row of the column titles, as the file counts rows (from 1). */
    static final int TITLE_ROW = 1;

    /** The hidden row of the field keys. */
    static final int KEY_ROW = 2;

    /** The first data row. */
    static final int FIRST_DATA_ROW = 3;

    /** Who wrote the file, in its properties; the format wants the version as XX.YYYY. */
    static final String APPLICATION = "SmartupCMS";

    static final String APP_VERSION = "1.0";

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String HEADER_FILL = "DCE6F2";

    private static final int MAX_SHEET_NAME = 31;

    private ImportSheets() {}

    /** The title of a column: its label in the reader's language, marked with an asterisk when a create needs it. */
    static String title(Column column, Function<String, String> text) {
        String label = column.label() != null ? column.label() : text.apply(column.labelKey());
        return column.required() ? label + " *" : label;
    }

    /** Writes the title and the hidden key of a column, styled as a header. */
    static void header(Worksheet sheet, int column, String title, String key) {
        sheet.value(TITLE_ROW - 1, column, title);
        sheet.style(TITLE_ROW - 1, column).bold().fillColor(HEADER_FILL).set();
        sheet.value(KEY_ROW - 1, column, key);
        sheet.width(column, 24);
    }

    /** Hides the key row and keeps the titles in sight. */
    static void finishHeader(Worksheet sheet) {
        sheet.hideRow(KEY_ROW - 1);
        sheet.freezePane(0, FIRST_DATA_ROW - 1);
    }

    /** A sheet name Excel accepts, unique among {@code taken}: no {@code []:*?/\}, at most 31 characters. */
    static String sheetName(String name, Set<String> taken) {
        String safe = name.replaceAll("[\\[\\]:*?/\\\\']", " ").strip();
        if (safe.isEmpty()) safe = "sheet";
        if (safe.length() > MAX_SHEET_NAME) safe = safe.substring(0, MAX_SHEET_NAME);
        String unique = safe;
        for (int i = 2; taken.contains(unique.toLowerCase(Locale.ROOT)); i++) {
            String suffix = " " + i;
            unique = safe.substring(0, Math.min(safe.length(), MAX_SHEET_NAME - suffix.length())) + suffix;
        }
        taken.add(unique.toLowerCase(Locale.ROOT));
        return unique;
    }
}
