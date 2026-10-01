package com.smartup24.cms.instance.units.service;

import com.smartup24.cms.instance.common.actor.ActorError;
import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.actor.AuditActorContext;
import com.smartup24.cms.instance.common.error.ConstraintCode;
import com.smartup24.cms.instance.common.error.ConstraintErrors;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.versioning.Versions;
import com.smartup24.cms.instance.units.api.CoefficientMissingException;
import com.smartup24.cms.instance.units.api.Unit;
import com.smartup24.cms.instance.units.api.UnitConversion;
import com.smartup24.cms.instance.units.api.UnitConversion.CoefficientRef;
import com.smartup24.cms.instance.units.api.UnitError;
import com.smartup24.cms.instance.units.api.Units;
import com.smartup24.cms.instance.units.repository.UnitRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The instance's units of measure and conversion by a dated coefficient; the SQL is in {@link UnitRepository} (plan
 * 10/10, item 4.2).
 *
 * <p>The core contains no unit code and no factor at all: the instance creates both through {@link #registerUnit}
 * and {@link #publishCoefficient}. A conversion uses only the direct published coefficient {@code from → to} valid
 * on the date: an inverse factor or a chain {@code a → b → c} is never derived, since that would produce a value
 * "from memory" rather than from a published coefficient.
 */
@Service
public class UnitService implements Units {

    /** The coefficient versions table, following the versioning standard ({@link Versions}). */
    public static final String COEFFICIENT_VERSIONS = UnitRepository.COEFFICIENT_VERSIONS;

    private static final List<ConstraintCode> CODES = ConstraintErrors.codes(UnitError.values(), ActorError.values());

    private final UnitRepository units;
    private final AuditActorContext actors;
    private final Versions versioning;
    private final JsonColumns jsonColumns;

    @Autowired
    public UnitService(UnitRepository units, AuditActorContext actors, Versions versioning, ObjectMapper json) {
        this.units = units;
        this.actors = actors;
        this.versioning = versioning;
        this.jsonColumns = new JsonColumns(json, "fnd_units");
    }

    /** A service built by hand, for tests and tools. */
    public UnitService(JdbcClient jdbc, AuditActorContext actors, Versions versioning, ObjectMapper json) {
        this(new UnitRepository(jdbc), actors, versioning, json);
    }

    /**
     * Registers a unit. {@code baseUnitCode} points to an existing unit; a base unit refers to itself. A unit without
     * a base unit is rejected with {@code fnd_unit_base_required}: otherwise {@link #toBase} could not tell "this is
     * a base unit" from "no base is set". The code format and the mandatory Uzbek name are checked by database
     * constraints.
     */
    @Transactional
    @Override
    public long registerUnit(String code, Map<String, String> nameI18n, String baseUnitCode, AuditActor actor) {
        if (baseUnitCode == null || baseUnitCode.isBlank()) {
            throw new ConstraintViolationException(UnitError.FND_UNIT_BASE_REQUIRED);
        }
        actors.apply(actor);
        String names = jsonColumns.object(nameI18n);
        return ConstraintErrors.translating(CODES, () -> units.insertUnit(code, names, baseUnitCode));
    }

    /** A unit by its code, as the instance sees it. */
    @Transactional(readOnly = true)
    @Override
    public Optional<Unit> findUnit(String code) {
        return units.findUnit(code);
    }

    /**
     * All of the instance's units ordered by code, for screen drop-down lists. The core does not care what
     * {@code nameI18n} contains.
     */
    @Transactional(readOnly = true)
    @Override
    public List<Unit> listUnits() {
        return units.listUnits();
    }

    /**
     * Publishes a coefficient value with the date it takes effect. The header for the unit pair is created on the
     * first call; the value goes into a new version under the versioning standard, which closes the previous version.
     */
    @Transactional
    @Override
    public CoefficientRef publishCoefficient(
            String fromUnit, String toUnit, BigDecimal factor, LocalDate validFrom, AuditActor actor) {
        if (factor == null) {
            throw new IllegalArgumentException("Множитель не задан");
        }
        actors.apply(actor);
        long coefficientId = units.findCoefficientId(fromUnit, toUnit)
                .orElseGet(() -> ConstraintErrors.translating(CODES, () -> units.insertCoefficient(fromUnit, toUnit)));
        int version = versioning.createDraft(COEFFICIENT_VERSIONS, coefficientId, actor);
        versioning.updateDraft(COEFFICIENT_VERSIONS, coefficientId, version, 0, Map.of("factor", factor), actor);
        versioning.publish(COEFFICIENT_VERSIONS, coefficientId, version, validFrom, null, actor);
        return new CoefficientRef(coefficientId, version);
    }

    /**
     * Converts a value as of a date. With no coefficient, {@link CoefficientMissingException} is thrown and no
     * value is returned. The units module does no rounding: the numeric multiplication result is returned as is.
     */
    @Transactional(readOnly = true)
    @Override
    public UnitConversion convert(BigDecimal value, String fromUnit, String toUnit, LocalDate date) {
        if (value == null) {
            throw new IllegalArgumentException("Значение не задано: пересчитывать нечего");
        }
        requireUnitCode(fromUnit);
        requireUnitCode(toUnit);
        if (fromUnit.equals(toUnit)) {
            return new UnitConversion(value, toUnit, null, date);
        }
        long coefficientId = units.findCoefficientId(fromUnit, toUnit)
                .orElseThrow(() -> new CoefficientMissingException(fromUnit, toUnit, date));
        int version = versioning
                .versionAt(COEFFICIENT_VERSIONS, coefficientId, date)
                .orElseThrow(() -> new CoefficientMissingException(fromUnit, toUnit, date));
        BigDecimal factor = units.factor(coefficientId, version);
        return new UnitConversion(value.multiply(factor), toUnit, new CoefficientRef(coefficientId, version), date);
    }

    /** Converts to the base unit; the unit's base is taken from the reference data, never from code. */
    @Transactional(readOnly = true)
    @Override
    public UnitConversion toBase(BigDecimal value, String unitCode, LocalDate date) {
        requireUnitCode(unitCode);
        Unit unit = findUnit(unitCode).orElseThrow(() -> new ConstraintViolationException(UnitError.FND_UNIT_UNKNOWN));
        String base = unit.baseUnitCode();
        if (base == null) {
            // The schema (V107) forbids this; the branch guards against data created without going through the facade
            throw new ConstraintViolationException(UnitError.FND_UNIT_BASE_REQUIRED);
        }
        if (base.equals(unitCode)) {
            return new UnitConversion(value, unitCode, null, date);
        }
        return convert(value, unitCode, base, date);
    }

    private static String requireUnitCode(String unitCode) {
        if (unitCode == null || unitCode.isBlank()) {
            throw new IllegalArgumentException("unit code required");
        }
        return unitCode;
    }
}
