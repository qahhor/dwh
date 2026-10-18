package com.smartup24.cms.instance.common.formula;

import com.smartup24.cms.core.error.FieldErrorItem;

/**
 * A formula that cannot give a value for these values (ADR-0042): {@code division_by_zero}, {@code number_too_large},
 * {@code text_too_long}, {@code invalid_date}, {@code invalid_argument} or {@code budget}. The save that met it answers
 * 422 addressed to the field or rule of the formula, never 500; the text is {@code error.formula.<reason>}.
 */
public final class FormulaFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String reason;

    public FormulaFailure(String reason) {
        super("formula failed: " + reason, null, false, false);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    /** The failure as the field error of a 422, addressed to {@code at}. */
    public FieldErrorItem fieldError(String at) {
        return FieldErrorItem.keyed(at, "formula_" + reason, "error.formula." + reason);
    }
}
