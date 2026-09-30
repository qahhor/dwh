package com.smartup24.cms.instance.fnd.units;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.fnd.FndActor;
import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.error.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.error.ConstraintViolationException;
import com.smartup24.cms.instance.fnd.error.FndSqlErrors;
import com.smartup24.cms.instance.fnd.units.FndConversion.FndCoefficientRef;
import com.smartup24.cms.instance.fnd.versioning.FndVersioning;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The instance's units of measure and conversion by a dated coefficient.
 *
 * <p>The core contains no unit code and no factor at all: the instance creates both through {@link #registerUnit}
 * and {@link #publishCoefficient}. A conversion uses only the direct published coefficient {@code from → to} valid
 * on the date: an inverse factor or a chain {@code a → b → c} is never derived, since that would produce a value
 * "from memory" rather than from a published coefficient.
 */
@Service
public class FndUnitService {

    /** The coefficient versions table, following the foundation's versioning standard ({@link FndVersioning}). */
    public static final String COEFFICIENT_VERSIONS = "fnd_unit_coefficient_versions";

    private final JdbcClient jdbc;
    private final FndActors actors;
    private final FndVersioning versioning;
    private final JsonColumns jsonColumns;

    public FndUnitService(JdbcClient jdbc, FndActors actors, FndVersioning versioning, ObjectMapper json) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.versioning = versioning;
        this.jsonColumns = new JsonColumns(json, "fnd_units");
    }

    /**
     * Registers a unit. {@code baseUnitCode} points to an existing unit; a base unit refers to itself. A unit without
     * a base unit is rejected with {@code fnd_unit_base_required}: otherwise {@link #toBase} could not tell "this is
     * a base unit" from "no base is set". The code format and the mandatory Uzbek name are checked by database
     * constraints.
     */
    @Transactional
    public long registerUnit(String code, Map<String, String> nameI18n, String baseUnitCode, FndActor actor) {
        if (baseUnitCode == null || baseUnitCode.isBlank()) {
            throw new ConstraintViolationException(ConstraintErrorCode.FND_UNIT_BASE_REQUIRED);
        }
        actors.apply(actor);
        String names = jsonColumns.object(nameI18n);
        return FndSqlErrors.translating(() -> jdbc.sql("insert into fnd_units (code, name_i18n, base_unit_code)"
                        + " values (:code, cast(:names as jsonb), :base) returning id")
                .param("code", code)
                .param("names", names)
                .param("base", baseUnitCode)
                .query(Long.class)
                .single());
    }

    /** A unit by its code, as the instance sees it. */
    @Transactional(readOnly = true)
    public Optional<FndUnit> findUnit(String code) {
        return jdbc.sql("select id, code, name_i18n::text as name_i18n, base_unit_code from fnd_units"
                        + " where code = :code")
                .param("code", code)
                .query((rs, rowNum) -> new FndUnit(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name_i18n"),
                        rs.getString("base_unit_code")))
                .optional();
    }

    /**
     * All of the instance's units ordered by code, for screen drop-down lists. The core does not care what
     * {@code nameI18n} contains.
     */
    @Transactional(readOnly = true)
    public List<FndUnit> listUnits() {
        return jdbc.sql("select id, code, name_i18n::text as name_i18n, base_unit_code from fnd_units order by code")
                .query((rs, rowNum) -> new FndUnit(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name_i18n"),
                        rs.getString("base_unit_code")))
                .list();
    }

    /**
     * Publishes a coefficient value with the date it takes effect. The header for the unit pair is created on the
     * first call; the value goes into a new version under the foundation's versioning standard, which closes the
     * previous version.
     */
    @Transactional
    public FndCoefficientRef publishCoefficient(
            String fromUnit, String toUnit, BigDecimal factor, LocalDate validFrom, FndActor actor) {
        if (factor == null) {
            throw new IllegalArgumentException("Множитель не задан");
        }
        actors.apply(actor);
        long coefficientId = coefficientId(fromUnit, toUnit)
                .orElseGet(() -> FndSqlErrors.translating(() -> jdbc.sql(
                                "insert into fnd_unit_coefficients (from_unit, to_unit) values (:from, :to) returning id")
                        .param("from", fromUnit)
                        .param("to", toUnit)
                        .query(Long.class)
                        .single()));
        int version = versioning.createDraft(COEFFICIENT_VERSIONS, coefficientId, actor);
        versioning.updateDraft(COEFFICIENT_VERSIONS, coefficientId, version, 0, Map.of("factor", factor), actor);
        versioning.publish(COEFFICIENT_VERSIONS, coefficientId, version, validFrom, null, actor);
        return new FndCoefficientRef(coefficientId, version);
    }

    /**
     * Converts a value as of a date. With no coefficient, {@link FndCoefficientMissingException} is thrown and no
     * value is returned. The foundation does no rounding: the numeric multiplication result is returned as is.
     */
    @Transactional(readOnly = true)
    public FndConversion convert(BigDecimal value, String fromUnit, String toUnit, LocalDate date) {
        if (value == null) {
            throw new IllegalArgumentException("Значение не задано: пересчитывать нечего");
        }
        requireUnitCode(fromUnit);
        requireUnitCode(toUnit);
        if (fromUnit.equals(toUnit)) {
            return new FndConversion(value, toUnit, null, date);
        }
        long coefficientId = coefficientId(fromUnit, toUnit)
                .orElseThrow(() -> new FndCoefficientMissingException(fromUnit, toUnit, date));
        int version = versioning
                .versionAt(COEFFICIENT_VERSIONS, coefficientId, date)
                .orElseThrow(() -> new FndCoefficientMissingException(fromUnit, toUnit, date));
        BigDecimal factor = jdbc.sql(
                        "select factor from " + COEFFICIENT_VERSIONS + " where coefficient_id = :id and version = :v")
                .param("id", coefficientId)
                .param("v", version)
                .query(BigDecimal.class)
                .single();
        return new FndConversion(value.multiply(factor), toUnit, new FndCoefficientRef(coefficientId, version), date);
    }

    /** Converts to the base unit; the unit's base is taken from the reference data, never from code. */
    @Transactional(readOnly = true)
    public FndConversion toBase(BigDecimal value, String unitCode, LocalDate date) {
        requireUnitCode(unitCode);
        FndUnit unit = findUnit(unitCode)
                .orElseThrow(() -> new ConstraintViolationException(ConstraintErrorCode.FND_UNIT_UNKNOWN));
        String base = unit.baseUnitCode();
        if (base == null) {
            // The schema (V107) forbids this; the branch guards against data created without going through the facade
            throw new ConstraintViolationException(ConstraintErrorCode.FND_UNIT_BASE_REQUIRED);
        }
        if (base.equals(unitCode)) {
            return new FndConversion(value, unitCode, null, date);
        }
        return convert(value, unitCode, base, date);
    }

    private static String requireUnitCode(String unitCode) {
        if (unitCode == null || unitCode.isBlank()) {
            throw new IllegalArgumentException("unit code required");
        }
        return unitCode;
    }

    private Optional<Long> coefficientId(String fromUnit, String toUnit) {
        return jdbc.sql("select id from fnd_unit_coefficients where from_unit = :from and to_unit = :to")
                .param("from", fromUnit)
                .param("to", toUnit)
                .query(Long.class)
                .optional();
    }

    /** An instance unit; {@code nameI18n} is returned as JSON text, since the core does not care what it contains. */
    public record FndUnit(long id, String code, String nameI18n, String baseUnitCode) {}
}
