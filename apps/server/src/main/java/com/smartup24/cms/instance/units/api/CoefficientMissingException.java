package com.smartup24.cms.instance.units.api;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import java.time.LocalDate;
import java.util.Map;

/**
 * There is no coefficient for the date: the conversion is not performed and no value is returned in any form,
 * because a value "from memory" (a guessed or stale factor) is forbidden. An inverse coefficient or a chain of
 * coefficients is never derived instead. The API answers with a conflict against the unit reference data, with the
 * text {@code error.units.coefficient_missing}.
 */
public class CoefficientMissingException extends ApiException {

    private final String fromUnit;
    private final String toUnit;
    private final LocalDate date;

    public CoefficientMissingException(String fromUnit, String toUnit, LocalDate date) {
        super(
                ErrorCode.CONFLICT,
                "error.units.coefficient_missing",
                Map.of("from", String.valueOf(fromUnit), "to", String.valueOf(toUnit), "date", String.valueOf(date)));
        this.fromUnit = fromUnit;
        this.toUnit = toUnit;
        this.date = date;
    }

    public String fromUnit() {
        return fromUnit;
    }

    public String toUnit() {
        return toUnit;
    }

    public LocalDate date() {
        return date;
    }
}
