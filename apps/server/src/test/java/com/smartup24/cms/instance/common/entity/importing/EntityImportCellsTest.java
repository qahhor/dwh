package com.smartup24.cms.instance.common.entity.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Column;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Option;
import com.smartup24.cms.platform.api.entity.field.FieldOptions;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A cell of an import file read as the value of its field (ADR-0032, 10.1). */
class EntityImportCellsTest {

    private static Column column(FieldType type, Option... options) {
        return new Column("f", "f.label", null, type, false, List.of(options));
    }

    private static @Nullable Object value(FieldType type, Object raw) {
        EntityImportCells.Cell cell = EntityImportCells.read(column(type), FieldOptions.NONE, raw);
        assertThat(cell.problem()).isNull();
        return cell.value();
    }

    @Test
    @DisplayName("text: a number without its fraction, a text stripped")
    void text() {
        assertThat(value(FieldType.TEXT, new BigDecimal("123.000"))).isEqualTo("123");
        assertThat(value(FieldType.TEXT, new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(value(FieldType.TEXT, "  Mixed  ")).isEqualTo("Mixed");
        assertThat(value(FieldType.TEXT, Boolean.TRUE)).isEqualTo("true");
    }

    @Test
    @DisplayName("number: a number as its plain text, a decimal comma as a point")
    void number() {
        assertThat(value(FieldType.NUMBER, new BigDecimal("12.50"))).isEqualTo("12.5");
        assertThat(value(FieldType.NUMBER, "12,5")).isEqualTo("12.5");
        assertThat(value(FieldType.NUMBER, "1,200,000")).isEqualTo("1,200,000");
    }

    @Test
    @DisplayName("flag: yes and no in the product's languages; anything else is refused")
    void flag() {
        assertThat(value(FieldType.BOOLEAN, Boolean.FALSE)).isEqualTo(false);
        assertThat(value(FieldType.BOOLEAN, "Да")).isEqualTo(true);
        assertThat(value(FieldType.BOOLEAN, "yo'q")).isEqualTo(false);
        assertThat(value(FieldType.BOOLEAN, new BigDecimal("1"))).isEqualTo(true);
        EntityImportCells.Cell refused = EntityImportCells.read(column(FieldType.BOOLEAN), FieldOptions.NONE, "maybe");
        assertThat(refused.problem()).isNotNull();
        assertThat(refused.problem().field()).isEqualTo("f");
        assertThat(refused.problem().code()).isEqualTo("invalid");
    }

    @Test
    @DisplayName("dates and times: the days Excel counts, or a text as it is")
    void datesAndTimes() {
        assertThat(value(FieldType.DATE, new BigDecimal("46296"))).isEqualTo("2026-10-01");
        assertThat(value(FieldType.DATE, "2026-10-01")).isEqualTo("2026-10-01");
        assertThat(value(FieldType.DATETIME, new BigDecimal("46296.5"))).isEqualTo("2026-10-01T12:00Z");
        assertThat(value(FieldType.TIME, new BigDecimal("0.375"))).isEqualTo("09:00");
        assertThat(value(FieldType.TIME, new BigDecimal("0.37501157407"))).isEqualTo("09:00:01");
    }

    @Test
    @DisplayName("choice: its code, or the code whose name the cell gives")
    void choice() {
        Column color = column(FieldType.SELECT, new Option("red", "c.red", "Red"), new Option("blue", null, null));
        assertThat(EntityImportCells.read(color, FieldOptions.NONE, "blue").value())
                .isEqualTo("blue");
        assertThat(EntityImportCells.read(color, FieldOptions.NONE, "RED").value())
                .isEqualTo("red");
        assertThat(EntityImportCells.read(color, FieldOptions.NONE, "green").value())
                .isEqualTo("green");
    }

    @Test
    @DisplayName("references: a whole number, a code as text, several keys by commas")
    void references() {
        assertThat(value(FieldType.REF, new BigDecimal("42"))).isEqualTo(42L);
        assertThat(value(FieldType.REF, new BigDecimal("4.2"))).isEqualTo("4.2");
        assertThat(value(FieldType.REF, "main")).isEqualTo("main");
        assertThat(value(FieldType.MULTI_REF, "1, 2;3")).isEqualTo(List.of(1L, 2L, 3L));
        assertThat(value(FieldType.MULTI_REF, new BigDecimal("7"))).isEqualTo(List.of(7L));
        assertThat(EntityImportCells.read(column(FieldType.MULTI_REF), FieldOptions.NONE, "1, two")
                        .problem())
                .isNotNull();
    }

    @Test
    @DisplayName("money: an amount and its currency, the only currency of the field, or the document's")
    void money() {
        assertThat(EntityImportCells.read(
                                column(FieldType.MONEY), FieldOptions.money(List.of("UZS", "USD")), "1 250.50 usd")
                        .value())
                .isEqualTo(Map.of("amount", "1250.50", "currency", "USD"));
        assertThat(EntityImportCells.read(
                                column(FieldType.MONEY), FieldOptions.money(List.of("UZS")), new BigDecimal("10"))
                        .value())
                .isEqualTo(Map.of("amount", "10", "currency", "UZS"));
        assertThat(EntityImportCells.read(
                                column(FieldType.MONEY),
                                FieldOptions.money(List.of("UZS")).withCurrencyFrom("currency"),
                                new BigDecimal("3.5"))
                        .value())
                .isEqualTo("3.5");
    }
}
