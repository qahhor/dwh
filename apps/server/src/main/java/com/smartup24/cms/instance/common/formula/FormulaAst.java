package com.smartup24.cms.instance.common.formula;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** The parse tree of a formula before its types are checked; each node keeps the 1-based position it starts at. */
sealed interface FormulaAst {
    int position();

    /** A number, a text, {@code true}/{@code false} or {@code null}; {@code value} is null only for {@code null}. */
    record Literal(FormulaType type, @Nullable String value, int position) implements FormulaAst {}

    /** A field key. */
    record Name(String name, int position) implements FormulaAst {}

    /** {@code -x} or {@code not x}. */
    record Unary(String operator, FormulaAst operand, int position) implements FormulaAst {}

    /** {@code a + b} and the other binary operators; the position is the operator's. */
    record Binary(String operator, FormulaAst left, FormulaAst right, int position) implements FormulaAst {}

    /** {@code name(arguments)}. */
    record Call(String function, List<FormulaAst> args, int position) implements FormulaAst {
        public Call {
            args = List.copyOf(args);
        }
    }
}
