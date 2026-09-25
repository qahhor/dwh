package com.greenwhite.dwh.instance.upl;

import com.greenwhite.dwh.instance.upl.upload.UplErrorReportBuilder;
import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.ErrorRow;
import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.ErrorsView;
import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.PackageRow;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Файл ошибок загрузки (роадмап п. 21): сводка, затем каждая ошибка с адресом и словами вместо кода. */
class UplErrorReportBuilderTest {

    /** Dictionary stand-in: known error codes get words with their parameters, other keys come back as keys. */
    private static final Map<String, String> WORDS = Map.of(
            "upl.err.UPL_CELL_KEY_MASK", "Ключ не подходит под маску {mask}",
            "upl.err.UPL_STRUCT_COLUMN_MISSING", "Нет колонки «{column}»",
            "upl.errfile.title", "Ошибки загрузки файла «{file}»",
            "upl.errfile.truncated", "Показаны {shown} из {total}");
    private static final BiFunction<String, Map<String, Object>, String> TEXT = (key, params) -> {
        String result = WORDS.getOrDefault(key, key);
        for (var entry : params.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    };

    private final UplErrorReportBuilder builder = new UplErrorReportBuilder();

    @Test
    @DisplayName("сводка пакета и строки ошибок: лист, строка, колонка, значение, слова; ошибка структуры без строки")
    void reportListsEveryErrorInWords() throws Exception {
        ErrorsView errors = new ErrorsView(5, List.of(
                new ErrorRow(1, "Лист1", 7, "Ключ", "12AB", "UPL_CELL_KEY_MASK", Map.of("mask", "^[0-9]{9}$")),
                new ErrorRow(2, "Лист1", null, "Сумма", null, "UPL_STRUCT_COLUMN_MISSING", Map.of("column", "Сумма")),
                new ErrorRow(3, "Лист2", 9, "Дата", "вчера", "UPL_SOMETHING_NEW", Map.of())));

        UplErrorReportBuilder.ReportFile file = builder.build(pkg("a_jan.xlsx"), errors, TEXT);

        assertThat(file.fileName()).isEqualTo("errors_a_jan.xlsx");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.content()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows.getFirst().getCellText(0)).isEqualTo("Ошибки загрузки файла «a_jan.xlsx»");
            assertThat(rows.stream().map(row -> row.getCellText(0)).toList()).contains("Показаны 3 из 5");
            Row header = rows.stream().filter(row -> "upl.pkg.errors.col.sheet".equals(row.getCellText(0))).findFirst().orElseThrow();
            List<Row> table = rows.stream().filter(row -> row.getRowNum() > header.getRowNum()).toList();
            assertThat(table).hasSize(3);
            assertThat(List.of(table.get(0).getCellText(0), table.get(0).getCellText(1), table.get(0).getCellText(2),
                    table.get(0).getCellText(3), table.get(0).getCellText(4)))
                    .containsExactly("Лист1", "7", "Ключ", "12AB", "Ключ не подходит под маску ^[0-9]{9}$");
            assertThat(table.get(1).getCellText(1)).isEmpty();
            assertThat(table.get(1).getCellText(4)).isEqualTo("Нет колонки «Сумма»");
            // A code without words stays visible, never as a raw dictionary key.
            assertThat(table.get(2).getCellText(4)).isEqualTo("UPL_SOMETHING_NEW");
        }
    }

    @Test
    @DisplayName("без ошибок файл говорит об этом, а не отдаёт пустую таблицу")
    void emptyReportSaysSo() throws Exception {
        byte[] content = builder.build(pkg("clean.xlsx"), new ErrorsView(0, List.of()), TEXT).content();

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(content))) {
            assertThat(workbook.getFirstSheet().read().stream().map(row -> row.getCellText(0)).toList())
                    .contains("upl.errfile.none");
        }
    }

    private static PackageRow pkg(String fileName) {
        return new PackageRow(1L, UUID.randomUUID(), 3L, "tax.monthly", "Налоги TEST", 2,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), UUID.randomUUID(), fileName, "abc", 1024L,
                "verified", 10, 7, 3, 5, null, null, null, null, Instant.parse("2026-02-01T08:30:00Z"), "anna");
    }
}
