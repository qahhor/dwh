package com.smartup24.cms.instance.fnd.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.api.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.api.ConstraintViolationException;
import com.smartup24.cms.instance.fnd.api.FndActor;
import com.smartup24.cms.instance.fnd.api.FndCoefficientMissingException;
import com.smartup24.cms.instance.fnd.api.FndConversion;
import com.smartup24.cms.instance.fnd.api.FndConversion.FndCoefficientRef;
import com.smartup24.cms.instance.fnd.api.FndVersion;
import com.smartup24.cms.instance.fnd.versioning.FndVersioning;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.fixtures.DepartmentFixture;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Units and dated coefficients of the foundation.
 *
 * <p>Every test runs over two department configurations A and B ({@link DepartmentFixture}): unit codes,
 * multipliers and dates are a parameter, not knowledge of the core. No real industry units exist in the core or
 * in the tests.
 */
class FndUnitServiceTest extends EmbeddedPostgresTest {

    @Autowired
    private FndUnitService units;

    @Autowired
    private FndVersioning versioning;

    @Autowired
    private FndActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    private FndActor actor;

    /** The instance configurations A and B. */
    static Stream<DepartmentFixture> departments() {
        return DepartmentFixture.departments();
    }

    @BeforeEach
    void cleanUnits() {
        actor = actors.system();
        tx.executeWithoutResult(status -> {
            actors.apply(actor);
            // Published versions are protected by a trigger, so the cleanup runs in maintenance mode (V102)
            jdbc.sql("select set_config('dwh.maintenance', 'on', true)")
                    .query(String.class)
                    .single();
            jdbc.sql("delete from fnd_unit_coefficient_versions").update();
            jdbc.sql("delete from fnd_unit_coefficients").update();
            jdbc.sql("delete from fnd_units").update();
        });
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-18: единицы экземпляра заводятся с базовой единицей и обязательным именем на узбекском")
    void registerUnits(DepartmentFixture dept) {
        String base = dept.baseUnit().code();
        String derived = dept.derivedUnit().code();
        String other = dept.otherUnit().code();
        long baseId = units.registerUnit(base, Map.of("uz", dept.baseUnit().nameUz()), base, actor);
        long derivedId = units.registerUnit(
                derived, Map.of("uz", dept.derivedUnit().nameUz(), "ru", "Единица TEST"), base, actor);
        assertThat(baseId).isPositive();
        assertThat(derivedId).isPositive();
        assertThat(units.findUnit(base).orElseThrow().baseUnitCode()).isEqualTo(base);
        // Uzbek Cyrillic and Latin text is kept without loss
        assertThat(units.findUnit(derived).orElseThrow().nameI18n())
                .contains(dept.derivedUnit().nameUz())
                .contains("Единица TEST");

        assertThat(codeOf(() -> units.registerUnit("", Map.of("uz", "TEST"), base, actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNITS_CK_CODE);
        assertThat(codeOf(() -> units.registerUnit("u".repeat(33), Map.of("uz", "TEST"), base, actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNITS_CK_CODE);
        assertThat(codeOf(() -> units.registerUnit("u test", Map.of("uz", "TEST"), base, actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNITS_CK_CODE);
        assertThat(codeOf(() -> units.registerUnit(other, Map.of("ru", "TEST"), base, actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNITS_CK_NAME_UZ);
        assertThat(codeOf(() -> units.registerUnit(other, Map.of("uz", "TEST"), "u_unknown_test", actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNITS_FK_BASE_UNIT);
        assertThat(codeOf(() -> units.registerUnit(base, Map.of("uz", "TEST"), base, actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNITS_UK_CODE);
        // A unit without a base unit is rejected both by the facade and by plain SQL (base_unit_code not null, V107)
        assertThat(codeOf(() -> units.registerUnit(other, Map.of("uz", "TEST"), null, actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNIT_BASE_REQUIRED);
        assertThat(codeOf(() -> units.registerUnit(other, Map.of("uz", "TEST"), " ", actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNIT_BASE_REQUIRED);
        assertThat(jdbc.sql("select is_nullable from information_schema.columns"
                                + " where table_name = 'fnd_units' and column_name = 'base_unit_code'")
                        .query(String.class)
                        .single())
                .isEqualTo("NO");
        assertThat(units.findUnit(other)).isEmpty();
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-19: коэффициент публикуется версией стандарта; ноль, минус и пара из одной единицы — отказ")
    void coefficientIsVersioned(DepartmentFixture dept) {
        registerUnits(dept.units());
        DepartmentFixture.Coefficient first = dept.firstCoefficient();
        FndCoefficientRef ref =
                units.publishCoefficient(first.from(), first.to(), first.factor(), first.validFrom(), actor);

        FndVersion version = versioning
                .find(FndUnitService.COEFFICIENT_VERSIONS, ref.coefficientId(), ref.version())
                .orElseThrow();
        assertThat(ref.version()).isEqualTo(1);
        assertThat(version.status()).isEqualTo(FndVersion.PUBLISHED);
        assertThat(version.publishedBy()).isEqualTo(actor.name());
        assertThat(factorOf(ref)).isEqualByComparingTo(first.factor());
        assertThat(jdbc.sql("select data_type from information_schema.columns where table_name ="
                                + " 'fnd_unit_coefficient_versions' and column_name = 'factor'")
                        .query(String.class)
                        .single())
                .isEqualTo("numeric");

        String derived = dept.derivedUnit().code();
        String other = dept.otherUnit().code();
        assertThat(codeOf(() -> units.publishCoefficient(derived, other, BigDecimal.ZERO, first.validFrom(), actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNIT_COEFFICIENT_VERSIONS_CK_FACTOR_POSITIVE);
        assertThat(codeOf(
                        () -> units.publishCoefficient(derived, other, new BigDecimal("-1"), first.validFrom(), actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNIT_COEFFICIENT_VERSIONS_CK_FACTOR_POSITIVE);
        assertThat(codeOf(() -> units.publishCoefficient(derived, derived, BigDecimal.TEN, first.validFrom(), actor)))
                .isEqualTo(ConstraintErrorCode.FND_UNIT_COEFFICIENTS_CK_DISTINCT);
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-20: пересчёт берёт коэффициент, действующий на дату, и возвращает ссылку на его версию")
    void convertUsesCoefficientOfTheDate(DepartmentFixture dept) {
        registerUnits(dept.units());
        DepartmentFixture.Coefficient early = dept.firstCoefficient();
        DepartmentFixture.Coefficient late = dept.secondCoefficient();
        FndCoefficientRef first = publish(early);
        FndCoefficientRef second = publish(late);
        BigDecimal value = new BigDecimal("2.5");
        LocalDate earlyDate = early.validFrom().plusDays(10);
        LocalDate lateDate = late.validFrom().plusDays(10);

        FndConversion earlyResult = units.convert(value, early.from(), early.to(), earlyDate);
        FndConversion lateResult = units.convert(value, late.from(), late.to(), lateDate);

        assertThat(earlyResult.value()).isEqualByComparingTo(value.multiply(early.factor()));
        assertThat(lateResult.value()).isEqualByComparingTo(value.multiply(late.factor()));
        assertThat(earlyResult.coefficient()).isEqualTo(first);
        assertThat(lateResult.coefficient()).isEqualTo(second);
        assertThat(earlyResult.date()).isEqualTo(earlyDate);
        assertThat(earlyResult.unit()).isEqualTo(early.to());
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-21: нет коэффициента на дату, обратный и цепочка — отказ; значение не возвращается")
    void missingCoefficientIsAnError(DepartmentFixture dept) {
        registerUnits(dept.units());
        DepartmentFixture.Coefficient first = dept.firstCoefficient();
        publish(first);
        String derived = dept.derivedUnit().code();
        String base = dept.baseUnit().code();
        String other = dept.otherUnit().code();
        LocalDate inForce = first.validFrom().plusDays(10);
        // A derived-to-separate pair exists only as a draft: a draft never takes part in the conversion
        long draftPair = tx.execute(status -> {
            actors.apply(actor);
            return jdbc.sql("insert into fnd_unit_coefficients (from_unit, to_unit) values (:f, :t) returning id")
                    .param("f", derived)
                    .param("t", other)
                    .query(Long.class)
                    .single();
        });
        versioning.createDraft(FndUnitService.COEFFICIENT_VERSIONS, draftPair, actor);

        assertThatThrownBy(() -> units.convert(
                        BigDecimal.ONE, derived, base, first.validFrom().minusDays(1)))
                .isInstanceOf(FndCoefficientMissingException.class);
        assertThatThrownBy(() -> units.convert(BigDecimal.ONE, base, derived, inForce))
                .isInstanceOf(FndCoefficientMissingException.class);
        FndCoefficientMissingException chain = (FndCoefficientMissingException)
                catchThrowable(() -> units.convert(BigDecimal.ONE, derived, other, inForce));
        assertThat(chain.fromUnit()).isEqualTo(derived);
        assertThat(chain.toUnit()).isEqualTo(other);
        assertThat(chain.date()).isEqualTo(inForce);

        FndConversion identity = units.convert(new BigDecimal("7.5"), derived, derived, inForce);
        assertThat(identity.value()).isEqualByComparingTo("7.5");
        assertThat(identity.coefficient()).isNull();
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-22: пересчёт в базовую единицу идёт по справочнику; базовая — тождество, чужая — отказ")
    void toBase(DepartmentFixture dept) {
        registerUnits(dept.units());
        DepartmentFixture.Coefficient first = dept.firstCoefficient();
        FndCoefficientRef ref = publish(first);
        LocalDate inForce = first.validFrom().plusDays(10);
        BigDecimal value = new BigDecimal("2.5");

        FndConversion converted = units.toBase(value, dept.derivedUnit().code(), inForce);
        assertThat(converted.value()).isEqualByComparingTo(value.multiply(first.factor()));
        assertThat(converted.unit()).isEqualTo(dept.baseUnit().code());
        assertThat(converted.coefficient()).isEqualTo(ref);

        FndConversion identity =
                units.toBase(new BigDecimal("3"), dept.baseUnit().code(), inForce);
        assertThat(identity.value()).isEqualByComparingTo("3");
        assertThat(identity.coefficient()).isNull();

        assertThat(codeOf(() -> units.toBase(BigDecimal.ONE, "u_unknown_test", inForce)))
                .isEqualTo(ConstraintErrorCode.FND_UNIT_UNKNOWN);
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-23: малый множитель и большое значение считаются точно; null — отказ, а не ноль")
    void boundaryValues(DepartmentFixture dept) {
        registerUnits(dept.units());
        String derived = dept.derivedUnit().code();
        String base = dept.baseUnit().code();
        LocalDate from = dept.firstCoefficient().validFrom();
        units.publishCoefficient(derived, base, new BigDecimal("0.000001"), from, actor);

        FndConversion converted = units.convert(new BigDecimal("1000000000000000"), derived, base, from.plusDays(10));
        assertThat(converted.value()).isEqualByComparingTo("1000000000");

        assertThatThrownBy(() -> units.convert(null, derived, base, from)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> units.convert(BigDecimal.ONE, null, base, from))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unit code required");
        assertThatThrownBy(() -> units.convert(BigDecimal.ONE, derived, " ", from))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unit code required");
        assertThatThrownBy(() -> units.toBase(BigDecimal.ONE, null, from))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unit code required");
        assertThatThrownBy(
                        () -> units.publishCoefficient(derived, dept.otherUnit().code(), null, from, actor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- helpers ----------

    private void registerUnits(List<DepartmentFixture.Unit> fixtureUnits) {
        for (DepartmentFixture.Unit unit : fixtureUnits) {
            units.registerUnit(unit.code(), Map.of("uz", unit.nameUz()), unit.base(), actor);
        }
    }

    private FndCoefficientRef publish(DepartmentFixture.Coefficient coefficient) {
        return units.publishCoefficient(
                coefficient.from(), coefficient.to(), coefficient.factor(), coefficient.validFrom(), actor);
    }

    private BigDecimal factorOf(FndCoefficientRef ref) {
        return jdbc.sql("select factor from fnd_unit_coefficient_versions"
                        + " where coefficient_id = :id and version = :v")
                .param("id", ref.coefficientId())
                .param("v", ref.version())
                .query(BigDecimal.class)
                .single();
    }

    private ConstraintErrorCode codeOf(Runnable action) {
        Throwable error = catchThrowable(action::run);
        assertThat(error).isInstanceOf(ConstraintViolationException.class);
        return ((ConstraintViolationException) error).code();
    }
}
