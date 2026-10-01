package com.smartup24.cms.instance.fnd.api;

import com.smartup24.cms.instance.fnd.api.FndConversion.FndCoefficientRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The instance's units of measure and conversion by a dated coefficient. A conversion uses only the direct published
 * coefficient valid on the date; with none, {@link FndCoefficientMissingException} is thrown and no value is returned.
 *
 * <p>Part of the foundation's contract (plan 10/10, item 4.2): callers depend on this interface, not on its
 * implementation, which may move to another package.
 */
public interface FndUnits {

    /** Registers a unit; {@code baseUnitCode} points to an existing unit, and a base unit refers to itself. */
    long registerUnit(String code, Map<String, String> nameI18n, String baseUnitCode, FndActor actor);

    /** A unit by its code. */
    Optional<FndUnit> findUnit(String code);

    /** All of the instance's units ordered by code. */
    List<FndUnit> listUnits();

    /** Publishes a coefficient value with the date it takes effect, as a new version of the unit pair. */
    FndCoefficientRef publishCoefficient(
            String fromUnit, String toUnit, BigDecimal factor, LocalDate validFrom, FndActor actor);

    /** Converts a value as of a date; no rounding is done. */
    FndConversion convert(BigDecimal value, String fromUnit, String toUnit, LocalDate date);

    /** Converts to the unit's base unit as of a date. */
    FndConversion toBase(BigDecimal value, String unitCode, LocalDate date);
}
