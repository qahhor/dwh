package com.greenwhite.dwh.instance.common.query;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Операция условия фильтра; {@link #wire()} — имя в DSL и в {@code query-meta}. */
public enum QueryOp {
    EQ, NE, IN, CONTAINS, STARTS_WITH, GT, GTE, LT, LTE, BETWEEN, EMPTY, NOT_EMPTY;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<QueryOp> fromWire(String wire) {
        return Arrays.stream(values()).filter(op -> op.wire().equals(wire)).findFirst();
    }

    /** Сколько значений ждёт операция: 0 — без значения, 1 — одно, 2 — пара, -1 — список. */
    int arity() {
        return switch (this) {
            case EMPTY, NOT_EMPTY -> 0;
            case BETWEEN -> 2;
            case IN -> -1;
            default -> 1;
        };
    }
}
