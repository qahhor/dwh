package com.smartup24.cms.instance.units.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The result of a conversion: the value, the unit, and a reference to the coefficient together with the date it was
 * chosen for. The reference is empty only for the identity {@code convert(v, u, u, d)}, which needs no coefficient.
 */
public record UnitConversion(BigDecimal value, String unit, CoefficientRef coefficient, LocalDate date) {

    /** A reference to the coefficient version used; the calling module must store it. */
    public record CoefficientRef(long coefficientId, int version) {}
}
