package com.smartup24.cms.instance.common.formula;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Evaluates a checked formula over a record (ADR-0042) with the null semantics of SQL: an arithmetic operand or a
 * compared value that is null makes the result null; {@code and}, {@code or} and {@code not} follow three-valued logic
 * and stop early ({@code false and x} is false, {@code true or x} is true without {@code x}); {@code &} reads null as
 * an empty text; {@code if} and {@code coalesce} evaluate only what they return. Every evaluated node counts toward a
 * budget, so even a formula at the size bounds ends in bounded time.
 */
final class FormulaEvaluator {
    private final Function<String, @Nullable Object> values;
    private final FormulaScope scope;
    private int steps;

    private FormulaEvaluator(Function<String, @Nullable Object> values, FormulaScope scope) {
        this.values = values;
        this.scope = scope;
    }

    /**
     * The value of {@code root}; {@code values} gives each field's raw value, read into the field's formula type here.
     *
     * @throws FormulaFailure when the formula cannot give a value for these values
     */
    static @Nullable Object evaluate(FormulaNode root, Function<String, @Nullable Object> values, FormulaScope scope) {
        return new FormulaEvaluator(values, scope).eval(root);
    }

    @Nullable
    Object eval(FormulaNode node) {
        if (++steps > Formula.MAX_STEPS) throw new FormulaFailure("budget");
        return switch (node.kind()) {
            case FormulaNode.LITERAL -> literal(node);
            case FormulaNode.FIELD -> FormulaValues.of(node.formulaType(), values.apply(String.valueOf(node.name())));
            case FormulaNode.UNARY -> unary(node);
            case FormulaNode.BINARY -> binary(node);
            default -> call(node);
        };
    }

    private static @Nullable Object literal(FormulaNode node) {
        String value = node.value();
        if (value == null) return null;
        return switch (node.formulaType()) {
            case NUMBER -> new BigDecimal(value);
            case BOOL -> Boolean.valueOf(value);
            default -> value;
        };
    }

    private @Nullable Object unary(FormulaNode node) {
        Object operand = eval(node.operands().getFirst());
        if (operand == null) return null;
        if ("not".equals(node.name())) return !((Boolean) operand);
        return FormulaNumbers.normalize(((BigDecimal) operand).negate());
    }

    private @Nullable Object binary(FormulaNode node) {
        String operator = String.valueOf(node.name());
        List<FormulaNode> operands = node.operands();
        if ("and".equals(operator) || "or".equals(operator)) return logic(operator, operands);
        Object left = eval(operands.getFirst());
        Object right = eval(operands.get(1));
        if ("&".equals(operator)) return FormulaTexts.bounded(FormulaValues.text(left) + FormulaValues.text(right));
        if (left == null || right == null) return null;
        return switch (operator) {
            case "+" -> FormulaNumbers.add((BigDecimal) left, (BigDecimal) right);
            case "-" -> FormulaNumbers.subtract((BigDecimal) left, (BigDecimal) right);
            case "*" -> FormulaNumbers.multiply((BigDecimal) left, (BigDecimal) right);
            case "/" -> FormulaNumbers.divide((BigDecimal) left, (BigDecimal) right);
            case "=" -> compare(left, right) == 0;
            case "!=" -> compare(left, right) != 0;
            case "<" -> compare(left, right) < 0;
            case "<=" -> compare(left, right) <= 0;
            case ">" -> compare(left, right) > 0;
            default -> compare(left, right) >= 0;
        };
    }

    /** Three-valued {@code and} and {@code or}: the right side is not evaluated when the left decides. */
    private @Nullable Object logic(String operator, List<FormulaNode> operands) {
        boolean and = "and".equals(operator);
        Boolean left = (Boolean) eval(operands.getFirst());
        if (left != null && left != and) return left;
        Boolean right = (Boolean) eval(operands.get(1));
        if (right != null && right != and) return right;
        if (left == null || right == null) return null;
        return and;
    }

    /** The order of two values of comparable types: numbers by value (a key as a number), the rest naturally. */
    static int compare(Object left, Object right) {
        if (left instanceof Long || right instanceof Long || left instanceof BigDecimal) {
            return decimal(left).compareTo(decimal(right));
        }
        return switch (left) {
            case String text -> text.compareTo((String) right);
            case Boolean flag -> Boolean.compare(flag, (Boolean) right);
            case LocalDate date -> date.compareTo((LocalDate) right);
            case Instant moment -> moment.compareTo((Instant) right);
            case LocalTime time -> time.compareTo((LocalTime) right);
            default -> throw new FormulaFailure("invalid_argument");
        };
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof Long key ? BigDecimal.valueOf(key) : (BigDecimal) value;
    }

    private @Nullable Object call(FormulaNode node) {
        String name = String.valueOf(node.name());
        List<FormulaNode> args = node.operands();
        switch (name) {
            case "if" -> {
                Boolean condition = (Boolean) eval(args.getFirst());
                return eval(Boolean.TRUE.equals(condition) ? args.get(1) : args.get(2));
            }
            case "coalesce" -> {
                for (FormulaNode arg : args) {
                    Object value = eval(arg);
                    if (value != null) return value;
                }
                return null;
            }
            default -> {
                List<@Nullable Object> evaluated = new ArrayList<>();
                for (FormulaNode arg : args) evaluated.add(eval(arg));
                return FormulaCalls.apply(name, evaluated, scope);
            }
        }
    }
}
