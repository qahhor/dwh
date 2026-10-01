package com.smartup24.cms.instance.common.query;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** A filter condition operation; {@link #wire()} is its name in the DSL and in {@code query-meta}. */
public enum QueryOp {
    EQ,
    NE,
    IN,
    CONTAINS,
    STARTS_WITH,
    GT,
    GTE,
    LT,
    LTE,
    BETWEEN,
    EMPTY,
    NOT_EMPTY;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<QueryOp> fromWire(String wire) {
        return Arrays.stream(values()).filter(op -> op.wire().equals(wire)).findFirst();
    }

    /** How many values the operation expects: 0 none, 1 one, 2 a pair, -1 a list. */
    int arity() {
        return switch (this) {
            case EMPTY, NOT_EMPTY -> 0;
            case BETWEEN -> 2;
            case IN -> -1;
            default -> 1;
        };
    }
}
