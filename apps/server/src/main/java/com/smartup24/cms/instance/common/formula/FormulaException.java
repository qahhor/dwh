package com.smartup24.cms.instance.common.formula;

import com.smartup24.cms.core.error.FieldErrorItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A formula that cannot be accepted (ADR-0042): why — {@code syntax}, {@code unknown_field}, {@code unknown_function},
 * {@code arity}, {@code type}, {@code argument}, {@code limit}, {@code self} or {@code cycle} — where (the 1-based
 * position of the character in the formula's text) and, for an unknown name, the name. Its text is the catalog key
 * {@code error.formula.<reason>}; it never carries a stack trace to the client.
 */
public final class FormulaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String reason;
    private final int position;
    private final @Nullable String name;

    public FormulaException(String reason, int position, @Nullable String name) {
        super("formula " + reason + " at " + position, null, false, false);
        this.reason = reason;
        this.position = position;
        this.name = name;
    }

    public FormulaException(String reason, int position) {
        this(reason, position, null);
    }

    public String reason() {
        return reason;
    }

    public int position() {
        return position;
    }

    public @Nullable String name() {
        return name;
    }

    /** The catalog key of the problem's text. */
    public String messageKey() {
        return "error.formula." + reason;
    }

    /** The parameters of the problem's text: its position and, when known, the name. */
    public Map<String, Object> params() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("position", position);
        if (name != null) params.put("name", name);
        return params;
    }

    /** The problem as the field error of a 422, addressed to {@code at}. */
    public FieldErrorItem fieldError(String at) {
        return FieldErrorItem.keyed(at, "formula_" + reason, messageKey(), params());
    }
}
