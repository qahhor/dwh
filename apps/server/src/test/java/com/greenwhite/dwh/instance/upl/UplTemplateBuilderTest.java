package com.greenwhite.dwh.instance.upl;

import com.greenwhite.dwh.instance.upl.format.UplFormatModel.Column;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.DataType;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.FileKind;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.FormatVersion;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.MatchBy;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.Periodicity;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.Sheet;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.Source;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.SourceType;
import com.greenwhite.dwh.instance.upl.format.UplFormatModel.Strictness;
import com.greenwhite.dwh.instance.upl.format.UplTemplateBuilder;
import com.greenwhite.dwh.instance.upl.parse.UplParseResult;
import com.greenwhite.dwh.instance.upl.parse.UplXlsxParser;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Шаблон файла по версии анкеты (роадмап п. 20): инструкция, заголовки там, где их ищет разбор, и проверки ввода. */
class UplTemplateBuilderTest {

    private static final Source SOURCE = new Source(1L, "tax.monthly", "Налоги TEST", "Org", null, Periodicity.MONTH,
            5, SourceType.FILE, Strictness.ERROR, 1, null, null, null, null);
    /** Dictionary stand-in: the key, then its parameters, so the tests see which text went where. */
    private static final BiFunction<String, Map<String, Object>, String> TEXT =
            (key, params) -> params.isEmpty() ? key : key + params;

    private final UplTemplateBuilder builder = new UplTemplateBuilder();

    @Test
    @DisplayName("xlsx: лист инструкции первым, затем листы анкеты с заголовками в строке заголовков; пустой шаблон проходит разбор")
    void xlsxTemplatePassesItsOwnFormat() throws Exception {
        FormatVersion format = format(MatchBy.HEADER, List.of(
                column(1, null, "№", DataType.INTEGER, true),
                column(2, null, "Название", DataType.TEXT, false),
                column(3, null, "Сумма", DataType.NUMBER, false),
                column(4, null, "Дата", DataType.DATE, true)));

        UplTemplateBuilder.TemplateFile file = builder.build(SOURCE, format, TEXT);

        assertThat(file.fileName()).isEqualTo("tax.monthly_v3.xlsx");
        assertThat(file.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.content()))) {
            List<String> names = workbook.getSheets().map(org.dhatim.fastexcel.reader.Sheet::getName).toList();
            assertThat(names).containsExactly("upl.template.instruction_sheet", "TEST лист");
            List<Row> instruction = workbook.getSheets().findFirst().orElseThrow().read();
            assertThat(instruction.getFirst().getCellText(0)).isEqualTo("upl.template.title{source=Налоги TEST, version=3}");
            assertThat(instruction.stream().map(row -> row.getCellText(1)).toList()).contains("№", "Название", "Сумма", "Дата");
            List<Row> data = workbook.getSheets().skip(1).findFirst().orElseThrow().read();
            Optional<Row> header = data.stream().filter(row -> row.getRowNum() == 2).findFirst();
            assertThat(header).isPresent();
            assertThat(List.of(header.get().getCellText(0), header.get().getCellText(1), header.get().getCellText(2),
                    header.get().getCellText(3))).containsExactly("№", "Название", "Сумма", "Дата");
        }

        UplParseResult parsed = new UplXlsxParser().parse(new ByteArrayInputStream(file.content()), format);
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.outcome()).isNotEqualTo(UplParseResult.Outcome.REJECTED);
    }

    @Test
    @DisplayName("сопоставление по позиции: заголовок стоит в колонке своей позиции в файле")
    void positionalColumnsSitAtTheirPositions() throws Exception {
        FormatVersion format = format(MatchBy.POSITION, List.of(
                column(1, 3, "Сумма", DataType.NUMBER, true),
                column(2, 1, "Ключ", DataType.TEXT, true)));

        byte[] content = builder.build(SOURCE, format, TEXT).content();

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(content))) {
            Row header = workbook.getSheets().skip(1).findFirst().orElseThrow().read().stream()
                    .filter(row -> row.getRowNum() == 2).findFirst().orElseThrow();
            assertThat(header.getCellText(0)).isEqualTo("Ключ");
            assertThat(header.getCellText(2)).isEqualTo("Сумма");
        }
        assertThat(new UplXlsxParser().parse(new ByteArrayInputStream(content), format).errors()).isEmpty();
    }

    @Test
    @DisplayName("CSV: строка заголовков в разделителе анкеты, UTF-8 с меткой порядка байтов, кавычки где нужно")
    void csvTemplateIsItsHeaderLine() {
        Sheet sheet = new Sheet(null, 1, "data", 1, null, List.of(
                column(1, null, "Код", DataType.TEXT, true),
                column(2, null, "Сумма; руб", DataType.NUMBER, false)));
        FormatVersion csv = new FormatVersion(1L, 2, null, null, "draft", null, null, 1, FileKind.CSV, "UTF-8", ";",
                MatchBy.HEADER, List.of(sheet));

        UplTemplateBuilder.TemplateFile file = builder.build(SOURCE, csv, TEXT);

        assertThat(file.fileName()).isEqualTo("tax.monthly_v2.csv");
        byte[] content = file.content();
        assertThat(Arrays.copyOf(content, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        assertThat(new String(content, 3, content.length - 3, StandardCharsets.UTF_8)).isEqualTo("Код;\"Сумма; руб\"\r\n");
    }

    private static FormatVersion format(MatchBy matchBy, List<Column> columns) {
        Sheet sheet = new Sheet(null, 1, "TEST лист", 2, null, columns);
        return new FormatVersion(1L, 3, LocalDate.of(2026, 1, 1), null, "published", null, null, 1,
                FileKind.XLSX, null, null, matchBy, List.of(sheet));
    }

    private static Column column(int ordinal, Integer filePosition, String nameInFile, DataType type, boolean required) {
        return new Column(null, ordinal, filePosition, nameInFile, "f" + ordinal, type, required,
                null, null, null, null, null, null);
    }
}
