package com.smartup24.cms.instance.common.formula;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The static types of the formula language (ADR-0042): an exact decimal, text, a yes/no, a date, a moment, a time of
 * day, the code of a choice, the key of a referenced row, and the type of the literal {@code null}, which joins any;
 * the lists of a document's rows (ADR-0043), which only the aggregates read.
 */
public enum FormulaType {
    NUMBER,
    TEXT,
    BOOL,
    DATE,
    DATETIME,
    TIME,
    OPTION,
    REF,
    NULL,
    /** The numbers of a column of a document's rows (ADR-0043): only {@code sum} and {@code count} read them. */
    NUMBERS,
    /** The rows of a document or a column of other values (ADR-0043): only {@code count} reads them. */
    ROWS;

    /** The name of the type as the AST and the web give it ({@code number}, {@code datetime}). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The type named {@code wire}. */
    public static FormulaType of(String wire) {
        return valueOf(wire.toUpperCase(Locale.ROOT));
    }

    /** Whether the type is a list of a document's rows, which no operator and no other function takes. */
    boolean list() {
        return this == NUMBERS || this == ROWS;
    }

    /** Whether values of the type have an order: numbers, texts and codes, dates, moments and times. */
    boolean ordered() {
        return this == NUMBER || this == TEXT || this == OPTION || this == DATE || this == DATETIME || this == TIME;
    }

    /** Whether the type reads as text: a text or the code of a choice. */
    boolean textual() {
        return this == TEXT || this == OPTION;
    }

    /**
     * The type both {@code a} and {@code b} are, or null when there is none: {@code null} joins any type, a text and a
     * code join as text.
     */
    static @Nullable FormulaType join(FormulaType a, FormulaType b) {
        if (a == NULL) return b;
        if (b == NULL || a == b) return a;
        if (a.textual() && b.textual()) return TEXT;
        return null;
    }

    /** Whether values of {@code a} and {@code b} may be compared for equality: one type, or a key and a number. */
    static boolean comparable(FormulaType a, FormulaType b) {
        return join(a, b) != null || (a == REF && b == NUMBER) || (a == NUMBER && b == REF);
    }
}
