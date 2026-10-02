package com.smartup24.cms.instance.report.imports;

import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Column;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Option;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Template;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * The import template of an entity (ADR-0032, 10.1): one sheet with a column per field the person may write — the
 * label in their language, a required one marked, the key in the hidden second row — and a hint sheet per choice that
 * lists its codes and their names. The file is written as the reports' exports are (ADR-0018): fastexcel, streamed.
 */
final class ImportTemplateWriter {

    private ImportTemplateWriter() {}

    /** Writes the template to {@code out}; the caller closes it. */
    static void write(OutputStream out, Template template, Function<String, String> text) throws IOException {
        Workbook workbook = new Workbook(out, ImportSheets.APPLICATION, ImportSheets.APP_VERSION);
        Set<String> names = new HashSet<>();
        Worksheet data = workbook.newWorksheet(ImportSheets.sheetName(template.entity(), names));
        List<Column> columns = template.columns();
        for (int c = 0; c < columns.size(); c++) {
            Column column = columns.get(c);
            ImportSheets.header(data, c, ImportSheets.title(column, text), column.key());
        }
        ImportSheets.finishHeader(data);
        for (Column column : columns) {
            if (column.options().isEmpty()) continue;
            String title = ImportSheets.title(column, text);
            Worksheet hint = workbook.newWorksheet(ImportSheets.sheetName(title.replace(" *", ""), names));
            hint.value(0, 0, text.apply("report.import.code"));
            hint.value(0, 1, text.apply("report.import.name"));
            hint.style(0, 0).bold().set();
            hint.style(0, 1).bold().set();
            hint.width(0, 24);
            hint.width(1, 40);
            int row = 1;
            for (Option option : column.options()) {
                hint.value(row, 0, option.code());
                hint.value(row, 1, name(option, text));
                row++;
            }
        }
        workbook.finish();
    }

    private static String name(Option option, Function<String, String> text) {
        if (option.label() != null) return option.label();
        return option.labelKey() == null ? option.code() : text.apply(option.labelKey());
    }
}
