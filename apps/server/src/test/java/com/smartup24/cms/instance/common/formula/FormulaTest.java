package com.smartup24.cms.instance.common.formula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.web.RequestZone;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The parts of the formula language the shared conformance cases do not reach (ADR-0042): the values a record holds in
 * the database, the holding and failing of conditions and rules, the problems as field errors and the request zone.
 */
class FormulaTest {
    private static final FormulaScope SCOPE = new FormulaScope(ZoneOffset.UTC, Instant.parse("2026-10-18T10:00:00Z"));

    @Test
    void storedValuesReadIntoTheirFormulaTypes() {
        assertThat(FormulaValues.of(FormulaType.NUMBER, new BigDecimal("2.500")))
                .isEqualTo(new BigDecimal("2.5"));
        assertThat(FormulaValues.of(FormulaType.NUMBER, 7L)).isEqualTo(new BigDecimal("7"));
        assertThat(FormulaValues.of(FormulaType.DATE, LocalDate.of(2026, 1, 2))).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(FormulaValues.of(FormulaType.DATE, java.sql.Date.valueOf("2026-01-02")))
                .isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(FormulaValues.of(FormulaType.DATE, "2026-01-02T10:00:00Z")).isEqualTo(LocalDate.of(2026, 1, 2));
        Instant moment = Instant.parse("2026-01-02T03:04:05.678Z");
        Instant second = Instant.parse("2026-01-02T03:04:05Z");
        assertThat(FormulaValues.of(FormulaType.DATETIME, moment)).isEqualTo(second);
        assertThat(FormulaValues.of(FormulaType.DATETIME, OffsetDateTime.parse("2026-01-02T08:04:05+05:00")))
                .isEqualTo(second);
        assertThat(FormulaValues.of(FormulaType.DATETIME, Timestamp.from(moment)))
                .isEqualTo(second);
        assertThat(FormulaValues.of(FormulaType.DATETIME, Date.from(moment))).isEqualTo(second);
        assertThat(FormulaValues.of(FormulaType.DATETIME, "yesterday")).isNull();
        assertThat(FormulaValues.of(FormulaType.TIME, LocalTime.of(9, 5, 7, 500)))
                .isEqualTo(LocalTime.of(9, 5, 7));
        assertThat(FormulaValues.of(FormulaType.TIME, "25:00")).isNull();
        assertThat(FormulaValues.of(FormulaType.REF, 42)).isEqualTo(42L);
        assertThat(FormulaValues.of(FormulaType.REF, "x")).isNull();
        assertThat(FormulaValues.of(FormulaType.BOOL, null)).isEqualTo(false);
        assertThat(FormulaValues.of(FormulaType.NULL, "x")).isNull();
        assertThat(FormulaValues.text(LocalTime.of(9, 5))).isEqualTo("09:05");
    }

    @Test
    void aConditionHoldsOnlyWhenTrueAndARuleFailsOnlyWhenFalse() {
        Formula formula = Formula.compile("qty > 1", Map.of("qty", FormulaType.NUMBER));
        Map<String, Object> empty = new HashMap<>();
        assertThat(formula.holds(empty::get, SCOPE)).isFalse();
        assertThat(formula.fails(empty::get, SCOPE)).isFalse();
        assertThat(formula.holds(Map.of("qty", "2")::get, SCOPE)).isTrue();
        assertThat(formula.fails(Map.of("qty", "1")::get, SCOPE)).isTrue();
        assertThat(formula.fields()).containsExactly(Map.entry("qty", 1));
        assertThat(formula.type()).isEqualTo(FormulaType.BOOL);
        assertThat(Formula.functions())
                .contains("round", "daysBetween", "sum", "count")
                .hasSize(27);
    }

    /** ADR-0043: a document's rows are read by dotted names, only by the aggregates, and a row reads its header. */
    @Test
    void aggregatesReadTheRowsOfADocumentAndARowReadsItsHeader() {
        Map<String, FormulaType> header = Map.of(
                "lines.items", FormulaType.ROWS,
                "lines.items.amount", FormulaType.NUMBERS,
                "lines.items.product", FormulaType.ROWS);
        Map<String, Object> record = Map.of(
                "lines.items", List.of(Map.of("a", 1), Map.of("a", 2), Map.of()),
                "lines.items.amount", List.of("10.5", "2", " "),
                "lines.items.product", Arrays.asList(5L, null, ""));
        assertThat(Formula.compile("sum(lines.items.amount)", header).evaluate(record::get, SCOPE))
                .isEqualTo(new BigDecimal("12.5"));
        assertThat(Formula.compile("count(lines.items)", header).evaluate(record::get, SCOPE))
                .isEqualTo(BigDecimal.valueOf(3));
        assertThat(Formula.compile("count(lines.items.product)", header).evaluate(record::get, SCOPE))
                .isEqualTo(BigDecimal.ONE);
        assertThat(Formula.compile("sum(lines.items.amount)", header).evaluate(Map.of()::get, SCOPE))
                .isEqualTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> Formula.compile("lines.items.amount + 1", header))
                .isInstanceOfSatisfying(
                        FormulaException.class,
                        problem -> assertThat(problem.reason()).isEqualTo("type"));
        assertThatThrownBy(() -> Formula.compile("round(lines.items.amount)", header))
                .isInstanceOfSatisfying(
                        FormulaException.class,
                        problem -> assertThat(problem.reason()).isEqualTo("type"));
        assertThatThrownBy(() -> Formula.compile("sum(lines.items.product)", header))
                .isInstanceOfSatisfying(
                        FormulaException.class,
                        problem -> assertThat(problem.reason()).isEqualTo("type"));
        assertThatThrownBy(() -> Formula.compile("sum(lines.other.amount)", header))
                .isInstanceOfSatisfying(FormulaException.class, problem -> {
                    assertThat(problem.reason()).isEqualTo("unknown_field");
                    assertThat(problem.name()).isEqualTo("lines.other.amount");
                });
        Formula line =
                Formula.compile("qty * doc.rate", Map.of("qty", FormulaType.NUMBER, "doc.rate", FormulaType.NUMBER));
        assertThat(line.fields()).containsOnlyKeys("qty", "doc.rate");
        assertThat(line.evaluate(Map.of("qty", "3", "doc.rate", "1.5")::get, SCOPE))
                .isEqualTo(new BigDecimal("4.5"));
        assertThatThrownBy(() -> Formula.compile("doc.", Map.of())).isInstanceOf(FormulaException.class);
    }

    @Test
    void problemsAndFailuresAreFieldErrorsWithCatalogKeys() {
        FormulaException problem = new FormulaException("unknown_field", 3, "foo");
        assertThat(problem.fieldError("fields[1].formula")).satisfies(item -> {
            assertThat(item.code()).isEqualTo("formula_unknown_field");
            assertThat(item.messageKey()).isEqualTo("error.formula.unknown_field");
            assertThat(item.params()).containsEntry("position", 3).containsEntry("name", "foo");
        });
        assertThat(new FormulaException("syntax", 1).params()).containsOnlyKeys("position");
        FormulaFailure failure = new FormulaFailure("budget");
        assertThat(failure.fieldError("total").messageKey()).isEqualTo("error.formula.budget");
        assertThat(failure.getStackTrace()).isEmpty();
    }

    @Test
    void comparingValuesOfDifferentKindsIsRefusedAtRunTime() {
        assertThatThrownBy(() -> FormulaEvaluator.compare(new Object(), new Object()))
                .isInstanceOf(FormulaFailure.class);
        assertThat(FormulaEvaluator.compare(5L, new BigDecimal("5"))).isZero();
        assertThat(FormulaEvaluator.compare(true, false)).isPositive();
    }

    @Test
    void theRequestZoneIsARegionOfTheZoneDatabaseOrUtc() {
        assertThat(RequestZone.current()).isEqualTo(ZoneOffset.UTC);
        assertThat(RequestZone.with(ZoneId.of("Asia/Tashkent"), RequestZone::current))
                .isEqualTo(ZoneId.of("Asia/Tashkent"));
        assertThat(RequestZone.current()).isEqualTo(ZoneOffset.UTC);
        assertThat(FormulaScope.current(java.time.Clock.fixed(SCOPE.now(), ZoneOffset.UTC))
                        .now())
                .isEqualTo(SCOPE.now());
    }
}
