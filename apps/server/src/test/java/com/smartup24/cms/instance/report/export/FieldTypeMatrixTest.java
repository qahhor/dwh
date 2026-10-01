package com.smartup24.cms.instance.report.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.FieldTypesFixture;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldType;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Plan 10/10, item 5.2 (ADR-0032, 4.8): every field type has its five parts — a branch of the server's check, a list
 * field with a filter (or the explicit "only there or not"), an export cell, a form control and a list cell on the web.
 * Parameterized by {@link FieldType#values()}: a new type without any part fails here; the web half is
 * {@code field-type-matrix.spec.ts}, and both agree on the list of types ({@link #theWebKnowsEveryType}).
 */
class FieldTypeMatrixTest {

    private static final String FILE_ID = "6f1c2a52-6b0e-4d3e-9a51-1f2d3c4b5a69";
    private static final Path WEB_TYPES = Path.of("../web/src/app/core/models/form-meta.models.ts");
    private static final Pattern WEB_LIST = Pattern.compile("FORM_FIELD_TYPES\\s*=\\s*\\[([^\\]]*)]");

    private static final EntityDefinition ENTITY = FieldTypesFixture.DEFINITION;

    /** The value of each type as a list row holds it ({@code EntityRowMapper}) and the cell text it exports as. */
    private static final Map<FieldType, List<Object>> EXPORTED = exported();

    @ParameterizedTest(name = "{0}")
    @EnumSource(FieldType.class)
    void theServerChecksEveryType(FieldType type) {
        Map<FieldType, List<Object>> samples = FieldTypesFixture.samples(FILE_ID);
        assertThat(samples).as("a valid and an invalid sample of " + type).containsKey(type);
        String key = FieldTypesFixture.keyOf(type);

        assertThat(problemsOf(key, samples.get(type).get(0)))
                .as("valid " + type)
                .isEmpty();
        assertThat(problemsOf(key, samples.get(type).get(1)))
                .as("invalid " + type)
                .extracting(FieldErrorItem::field)
                .contains(key);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FieldType.class)
    void everyTypeHasAListFieldWithAFilter(FieldType type) {
        EntityField field = field(type);
        List<QueryField> listed = field.queryFields("t");

        assertThat(listed).as("list field of " + type).isNotEmpty();
        QueryField list = listed.getFirst();
        assertThat(list.type()).isEqualTo(type.listType());
        assertThat(list.ops()).as("filter of " + type).isNotEmpty();
        if (list.type() == QueryFieldType.OBJECT) {
            assertThat(list.ops()).extracting(op -> op.wire()).containsExactlyInAnyOrder("empty", "not_empty");
        }
        assertThat(list.format()).isEqualTo(type.formatted() ? type.wire() : null);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FieldType.class)
    void everyTypeIsExported(FieldType type) throws IOException {
        assertThat(EXPORTED).as("export sample of " + type).containsKey(type);
        QueryField list = field(type).queryFields("t").getFirst();
        if (type == FieldType.ENUM) {
            list = list.withEnumeration(Map.of("kg", "Kilogram"));
        }
        Map<String, Object> item = new HashMap<>();
        item.put(list.key(), EXPORTED.get(type).get(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportWorkbookWriter writer =
                new ExportWorkbookWriter(out, "matrix", List.of(list), key -> "T:" + key, "SmartupCMS", "1.0")) {
            writer.add(item);
        }
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(1).getCellText(0))
                    .as("cell of " + type)
                    .isEqualTo(EXPORTED.get(type).get(1));
        }
    }

    @Test
    void theCurrencyOfMoneyIsExportedFromItsMoney() throws IOException {
        QueryField currency = field(FieldType.MONEY).queryFields("t").get(1);
        assertThat(currency.key()).isEqualTo("totalCurrency");
        assertThat(currency.defaultVisible()).isFalse();
        assertThat(currency.enumValues()).containsExactly("UZS", "USD");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportWorkbookWriter writer =
                new ExportWorkbookWriter(out, "money", List.of(currency), key -> "T:" + key, "SmartupCMS", "1.0")) {
            writer.add(Map.of("total", Map.of("amount", "10", "currency", "USD")));
        }
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(workbook.getFirstSheet().read().get(1).getCellText(0)).isEqualTo("USD");
        }
        assertThat(ExportFormats.moneyFormat("UZS")).isEqualTo("#,##0.00 \"UZS\"");
        assertThat(ExportFormats.moneyFormat("JPY")).isEqualTo("#,##0 \"JPY\"");
    }

    /** The web's list of form field types is the server's, in the same order (ADR-0032, 4.8). */
    @Test
    void theWebKnowsEveryType() throws IOException {
        Matcher list = WEB_LIST.matcher(Files.readString(WEB_TYPES, StandardCharsets.UTF_8));
        assertThat(list.find()).as("FORM_FIELD_TYPES in " + WEB_TYPES).isTrue();
        List<String> web = Arrays.stream(list.group(1).split(","))
                .map(item -> item.strip().replace("'", ""))
                .filter(item -> !item.isEmpty())
                .toList();
        assertThat(web)
                .containsExactlyElementsOf(
                        Arrays.stream(FieldType.values()).map(FieldType::wire).toList());
    }

    private static List<FieldErrorItem> problemsOf(String key, Object value) {
        Map<String, Object> record = validRecord();
        record.put(key, value);
        return EntityValidator.problems(ENTITY, record, false).stream()
                .filter(problem -> problem.field().equals(key))
                .toList();
    }

    private static Map<String, Object> validRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        FieldTypesFixture.samples(FILE_ID)
                .forEach((type, values) -> record.put(FieldTypesFixture.keyOf(type), values.get(0)));
        return record;
    }

    private static EntityField field(FieldType type) {
        String key = FieldTypesFixture.keyOf(type);
        return Objects.requireNonNull(ENTITY.model()).fields().stream()
                .filter(field -> field.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static Map<FieldType, List<Object>> exported() {
        Map<FieldType, List<Object>> values = new LinkedHashMap<>();
        values.put(FieldType.TEXT, List.of("Shelf", "Shelf"));
        values.put(FieldType.TEXTAREA, List.of("Two lines", "Two lines"));
        values.put(FieldType.MARKDOWN, List.of("**bold**", "**bold**"));
        values.put(FieldType.NUMBER, List.of(new BigDecimal("12.5"), "12.5"));
        values.put(FieldType.DATE, List.of("2026-10-01", "46296.0"));
        values.put(FieldType.DATETIME, List.of("2026-10-01T09:30:00Z", "46296.395833333336"));
        values.put(FieldType.TIME, List.of("09:30", "09:30"));
        values.put(FieldType.BOOLEAN, List.of(true, "T:common.yes"));
        values.put(FieldType.SELECT, List.of("wholesale", "T:test.kind_wholesale"));
        values.put(FieldType.REF, List.of(5, "5"));
        values.put(FieldType.EMAIL, List.of("ann@example.com", "ann@example.com"));
        values.put(FieldType.PHONE, List.of("+998901234567", "+998901234567"));
        values.put(FieldType.URL, List.of("https://example.com/a", "https://example.com/a"));
        values.put(FieldType.MONEY, List.of(Map.of("amount", "1250.50", "currency", "UZS"), "1250.50"));
        values.put(FieldType.ENUM, List.of("kg", "Kilogram"));
        values.put(FieldType.MULTI_REF, List.of(List.of(3L, 9L), "3, 9"));
        values.put(FieldType.FILE, List.of(Map.of("id", FILE_ID, "name", "act.pdf"), "act.pdf"));
        values.put(FieldType.IMAGE, List.of(Map.of("id", FILE_ID, "name", "shelf.png"), "shelf.png"));
        values.put(FieldType.JSON, List.of(Map.of("a", 1), "{\"a\":1}"));
        return values;
    }
}
