package com.smartup24.cms.instance.common.formula;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A checked formula (ADR-0042): its text, its checked tree — which the server evaluates and form-meta gives the web —
 * its static type and the fields it reads, each with the position of the character it is first read at. Formulas never become SQL or code: the tree holds only the
 * fixed operators and functions of {@link FormulaFunctions} and the fields it was checked against.
 *
 * <p>The bounds: a text of at most {@link #MAX_LENGTH} characters, at most {@link #MAX_NODES} nodes nested at most
 * {@link #MAX_DEPTH} deep, at most {@link #MAX_ARGS} arguments of a call and {@link #MAX_LITERAL} characters of a text
 * literal; values of at most {@link #MAX_TEXT} characters and numbers of {@link FormulaNumbers#MAX_INTEGER_DIGITS}
 * digits with {@link #MAX_SCALE} decimals; at most {@link #MAX_STEPS} evaluated nodes.
 */
public record Formula(String source, FormulaNode root, FormulaType type, Map<String, Integer> fields) {
    public static final int MAX_LENGTH = 1000;
    public static final int MAX_NODES = 200;
    public static final int MAX_DEPTH = 20;
    public static final int MAX_ARGS = 10;
    public static final int MAX_LITERAL = 200;
    public static final int MAX_TEXT = 4000;
    public static final int MAX_SCALE = 10;
    public static final int MAX_STEPS = 10_000;

    public Formula {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * Parses and checks {@code source} against the fields it may read, by key and formula type.
     *
     * @throws FormulaException naming the first problem and its position
     */
    public static Formula compile(String source, Map<String, FormulaType> fields) {
        FormulaChecker.Checked checked = FormulaChecker.check(FormulaParser.parse(source), fields);
        return new Formula(source, checked.root(), checked.root().formulaType(), checked.fields());
    }

    /** The names of the functions, for the editor's list. */
    public static Set<String> functions() {
        return FormulaFunctions.NAMES;
    }

    /**
     * The value over a record: {@code values} gives a field's raw value by key, read into its formula type.
     *
     * @throws FormulaFailure when the formula cannot give a value for these values
     */
    public @Nullable Object evaluate(Function<String, @Nullable Object> values, FormulaScope scope) {
        return FormulaEvaluator.evaluate(root, values, scope);
    }

    /** Whether the formula holds over a record: true only when it is true — false and null do not hold. */
    public boolean holds(Function<String, @Nullable Object> values, FormulaScope scope) {
        return Boolean.TRUE.equals(evaluate(values, scope));
    }

    /** Whether a rule's formula fails over a record: only false fails — null passes, as a SQL check does. */
    public boolean fails(Function<String, @Nullable Object> values, FormulaScope scope) {
        return Boolean.FALSE.equals(evaluate(values, scope));
    }
}
