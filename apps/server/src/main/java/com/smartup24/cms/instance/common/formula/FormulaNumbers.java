package com.smartup24.cms.instance.common.formula;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The exact decimal arithmetic of formulas (ADR-0042). Every result keeps at most {@link Formula#MAX_SCALE} decimals —
 * more are rounded half away from zero ({@code HALF_UP}) — and loses its trailing zeros; one with more than
 * {@link #MAX_INTEGER_DIGITS} digits before the point is {@code number_too_large}. A division rounds to
 * {@link Formula#MAX_SCALE} decimals; one by zero is {@code division_by_zero}. The web computes the same with BigInt.
 */
public final class FormulaNumbers {
    /** Digits a number may have before its decimal point. */
    public static final int MAX_INTEGER_DIGITS = 24;

    private FormulaNumbers() {}

    /** The number with at most the decimals a formula keeps, without trailing zeros, within the bounds. */
    public static BigDecimal normalize(BigDecimal value) {
        BigDecimal rounded =
                value.scale() > Formula.MAX_SCALE ? value.setScale(Formula.MAX_SCALE, RoundingMode.HALF_UP) : value;
        BigDecimal stripped = rounded.signum() == 0 ? BigDecimal.ZERO : rounded.stripTrailingZeros();
        if (stripped.scale() < 0) stripped = stripped.setScale(0, RoundingMode.UNNECESSARY);
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) throw new FormulaFailure("number_too_large");
        return stripped;
    }

    /** The canonical text of a number: plain digits, a point only before decimals ({@code -12.5}, {@code 3}). */
    public static String text(BigDecimal value) {
        return value.toPlainString();
    }

    static BigDecimal add(BigDecimal a, BigDecimal b) {
        return normalize(a.add(b));
    }

    static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return normalize(a.subtract(b));
    }

    static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return normalize(a.multiply(b));
    }

    static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (b.signum() == 0) throw new FormulaFailure("division_by_zero");
        return normalize(a.divide(b, Formula.MAX_SCALE, RoundingMode.HALF_UP));
    }

    /** {@code value} rounded half away from zero to {@code digits} decimals. */
    static BigDecimal round(BigDecimal value, int digits) {
        return normalize(value.setScale(digits, RoundingMode.HALF_UP));
    }

    /** The value as a whole number, or {@code invalid_argument} when it has decimals or is out of {@code int}. */
    static int whole(BigDecimal value) {
        if (value.stripTrailingZeros().scale() > 0 || value.abs().compareTo(BigDecimal.valueOf(1_000_000)) > 0)
            throw new FormulaFailure("invalid_argument");
        return value.intValue();
    }
}
