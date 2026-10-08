package com.smartup24.cms.instance.report.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Column;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Option;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Row;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Template;
import com.smartup24.cms.instance.common.xlsx.XlsxGuard;
import com.smartup24.cms.instance.common.xlsx.XlsxLimits;
import com.smartup24.cms.instance.report.repository.ReportImportRepository.ErrorRow;
import com.smartup24.cms.instance.support.XlsxBombs;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The files of an import (ADR-0032, 10.1): template, reading, structure, texts and report, without a database. */
class ImportSheetsTest {

    private static final Function<String, String> TEXT = key -> "T(" + key + ")";

    @TempDir
    Path dir;

    @Test
    @DisplayName("a sheet name Excel accepts: no forbidden characters, at most 31 characters, unique")
    void sheetNames() {
        Set<String> taken = new HashSet<>();
        assertThat(ImportSheets.sheetName("a/b:c", taken)).isEqualTo("a b c");
        assertThat(ImportSheets.sheetName("A/B:C", taken)).isEqualTo("A B C 2");
        assertThat(ImportSheets.sheetName("  ", taken)).isEqualTo("sheet");
        String longName = "x".repeat(40);
        assertThat(ImportSheets.sheetName(longName, taken)).hasSize(31);
        assertThat(ImportSheets.sheetName(longName, taken)).hasSize(31).endsWith(" 2");
    }

    @Test
    @DisplayName("titles: the ready label or the key's text, a required one marked")
    void titles() {
        assertThat(ImportSheets.title(new Column("a", "k.a", null, FieldType.TEXT, true, List.of()), TEXT))
                .isEqualTo("T(k.a) *");
        assertThat(ImportSheets.title(new Column("b", "", "Ready", FieldType.TEXT, false, List.of()), TEXT))
                .isEqualTo("Ready");
    }

    @Test
    @DisplayName("texts of problems: the key filled with its parameters, a text without a key as it is, cut")
    void problemTexts() {
        assertThat(ImportRunner.fill("{a} and {b}", Map.of("a", 1, "b", "two"))).isEqualTo("1 and two");
        ErrorRow keyed = ImportRunner.errorRow(
                5, FieldErrorItem.keyed("rows[5].x", "c", "k.x", Map.of("n", 3)), key -> "n is {n}");
        assertThat(keyed).isEqualTo(new ErrorRow(5, "rows[5].x", "c", "n is 3"));
        ErrorRow plain = ImportRunner.errorRow(6, new FieldErrorItem("f".repeat(300), "c", "m".repeat(2000)), TEXT);
        assertThat(plain.field()).hasSize(200);
        assertThat(plain.message()).hasSize(1000);
    }

    @Test
    @DisplayName("the key row: unknown and repeated columns, and no column at all, refuse the file")
    void structure() {
        Template template = new Template(
                "x.items",
                "code",
                List.of(
                        new Column("code", "k", null, FieldType.TEXT, true, List.of()),
                        new Column("name", "n", null, FieldType.TEXT, false, List.of())));
        assertThat(ImportRunner.ImportStructure.problems(template, Arrays.asList("code", null, "", "name"), TEXT))
                .isEmpty();
        assertThat(ImportRunner.ImportStructure.problems(template, List.of("code", "secret", "code"), TEXT))
                .extracting(ErrorRow::field, ErrorRow::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("columns[1]", "unknown_column"),
                        org.assertj.core.groups.Tuple.tuple("columns[2]", "duplicate_column"));
        assertThat(ImportRunner.ImportStructure.problems(template, List.of(), TEXT))
                .extracting(ErrorRow::code)
                .containsExactly("required");
    }

    @Test
    @DisplayName("a filled file: keys, data rows with their kinds of cells, the count, a skip and a limit")
    void readsAFile() throws Exception {
        Path file = dir.resolve("filled.xlsx");
        try (OutputStream out = Files.newOutputStream(file)) {
            Workbook book = new Workbook(out, "test", "1.0");
            Worksheet sheet = book.newWorksheet("data");
            sheet.value(0, 0, "Code");
            sheet.value(1, 0, "code");
            sheet.value(1, 1, "flag");
            sheet.value(1, 2, "amount");
            sheet.value(2, 0, "a");
            sheet.value(2, 1, true);
            sheet.value(2, 2, 12.5);
            sheet.value(3, 0, "   ");
            sheet.value(4, 0, "b");
            sheet.formula(4, 2, "1+1");
            sheet.value(5, 3, "beyond the keys");
            book.finish();
        }
        try (ImportFile read = ImportFile.open(file, XlsxLimits.defaults())) {
            List<String> keys = read.keys();
            assertThat(keys).containsExactly("code", "flag", "amount");
            assertThat(read.count(keys, 10)).isEqualTo(2);
            assertThat(read.count(keys, 1)).isEqualTo(2);
            List<Row> rows = new ArrayList<>();
            read.rows(keys, 0, rows::add, 10);
            assertThat(rows).extracting(Row::number).containsExactly(3, 5);
            assertThat(rows.getFirst().cells())
                    .containsEntry("code", "a")
                    .containsEntry("flag", true)
                    .containsEntry("amount", new BigDecimal("12.5"));
            List<Row> skipped = new ArrayList<>();
            read.rows(keys, 1, skipped::add, 10);
            assertThat(skipped).extracting(Row::number).containsExactly(5);
        }
    }

    @Test
    @DisplayName("a file that is no workbook cannot be read")
    void unreadable() throws Exception {
        Path file = dir.resolve("broken.xlsx");
        Files.writeString(file, "not a zip");
        assertThatThrownBy(() -> ImportFile.open(file, XlsxLimits.defaults()))
                .isInstanceOf(ImportFile.Unreadable.class);
    }

    @Test
    @DisplayName("plan 10/10, item 7.6: a zip bomb is refused before the reader opens it")
    void zipBombIsRefused() throws Exception {
        Path bomb = XlsxBombs.bomb(dir.resolve("bomb.xlsx"), 256);

        assertThatThrownBy(() -> ImportFile.open(bomb, XlsxLimits.defaults()))
                .isInstanceOf(ImportFile.Unreadable.class)
                .cause()
                .isInstanceOfSatisfying(
                        XlsxGuard.Rejected.class,
                        rejected -> assertThat(rejected.limit()).isEqualTo(XlsxGuard.Limit.COMPRESSION_RATIO));
    }

    @Test
    @DisplayName("the template: titles, hidden keys and a hint sheet per choice with codes and names")
    void template() throws Exception {
        Template template = new Template(
                "x.items",
                "code",
                List.of(
                        new Column("code", "k.code", null, FieldType.TEXT, true, List.of()),
                        new Column(
                                "color",
                                "k.color",
                                null,
                                FieldType.SELECT,
                                false,
                                List.of(
                                        new Option("red", "k.red", null),
                                        new Option("blue", null, "Blue"),
                                        new Option("green", null, null)))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImportTemplateWriter.write(out, template, TEXT);
        try (ReadableWorkbook book = new ReadableWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var sheets = book.getSheets().toList();
            assertThat(sheets).hasSize(2);
            var data = sheets.get(0).read();
            assertThat(data.get(0).getCellText(0)).isEqualTo("T(k.code) *");
            assertThat(data.get(1).getCellText(1)).isEqualTo("color");
            var hint = sheets.get(1).read();
            assertThat(hint).hasSize(4);
            assertThat(hint.get(1).getCellText(1)).isEqualTo("T(k.red)");
            assertThat(hint.get(2).getCellText(1)).isEqualTo("Blue");
            assertThat(hint.get(3).getCellText(1)).isEqualTo("green");
        }
    }

    @Test
    @DisplayName("the report: the rows of the file and a column with the problems of each refused one")
    void report() throws Exception {
        Path file = dir.resolve("source.xlsx");
        try (OutputStream out = Files.newOutputStream(file)) {
            Workbook book = new Workbook(out, "test", "1.0");
            Worksheet sheet = book.newWorksheet("data");
            sheet.value(0, 0, "Code");
            sheet.value(1, 0, "code");
            sheet.value(1, 1, "count");
            sheet.value(2, 0, "a");
            sheet.value(2, 1, 3);
            sheet.value(3, 0, "b");
            sheet.value(3, 1, false);
            book.finish();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImportReportWriter.write(
                file,
                out,
                "Errors",
                (from, to) -> List.of(
                        new ErrorRow(4, "rows[4].count", "invalid", "Not a number"),
                        new ErrorRow(4, "rows[4]", "conflict", "Taken")));
        try (ReadableWorkbook book = new ReadableWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var rows = book.getFirstSheet().read();
            assertThat(rows.get(0).getCellText(0)).isEqualTo("Code");
            assertThat(rows.get(0).getCellText(1))
                    .as("a title the source lacks: its key")
                    .isEqualTo("count");
            assertThat(rows.get(0).getCellText(2)).isEqualTo("Errors");
            assertThat(rows.get(2).getCellCount()).as("a row without problems").isEqualTo(2);
            assertThat(rows.get(3).getCellText(2)).isEqualTo("count: Not a number\nTaken");
        }
    }
}
