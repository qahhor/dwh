package com.smartup24.cms.instance.common.formula;

import static com.smartup24.cms.instance.common.formula.FormulaType.BOOL;
import static com.smartup24.cms.instance.common.formula.FormulaType.DATE;
import static com.smartup24.cms.instance.common.formula.FormulaType.DATETIME;
import static com.smartup24.cms.instance.common.formula.FormulaType.NUMBER;
import static com.smartup24.cms.instance.common.formula.FormulaType.TEXT;
import static com.smartup24.cms.instance.common.formula.FormulaType.TIME;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The fixed functions of the formula language (ADR-0042) and their types. Numbers: {@code round(x[, digits])},
 * {@code abs}, {@code min}, {@code max}; choice: {@code if(condition, then, else)}, {@code coalesce}, {@code isEmpty};
 * text: {@code len}, {@code upper}, {@code lower}, {@code trim}, {@code contains}; dates: {@code today()},
 * {@code now()}, {@code date(y, m, d)}, {@code year}, {@code month}, {@code day}, {@code weekday}, {@code hour},
 * {@code minute}, {@code dateOf}, {@code addDays}, {@code addMonths}, {@code daysBetween}; rows of a document (ADR-0043):
 * {@code sum(lines.items.amount)}, {@code count(lines.items)}, {@code count(lines.items.product)}.
 */
final class FormulaFunctions {
    /** The names of the functions, as the editor lists them. */
    static final Set<String> NAMES = Set.of(
            "round",
            "abs",
            "min",
            "max",
            "if",
            "coalesce",
            "isEmpty",
            "len",
            "upper",
            "lower",
            "trim",
            "contains",
            "today",
            "now",
            "date",
            "year",
            "month",
            "day",
            "weekday",
            "hour",
            "minute",
            "dateOf",
            "addDays",
            "addMonths",
            "daysBetween",
            "sum",
            "count");

    /** The functions over the rows of a document (ADR-0043): the only ones that take a list. */
    static final Set<String> AGGREGATES = Set.of("sum", "count");

    private FormulaFunctions() {}

    /** The type of a call of {@code name} with {@code args}, or a problem at {@code position}. */
    static FormulaType type(String name, List<FormulaNode> args, int position) {
        if (!NAMES.contains(name)) throw new FormulaException("unknown_function", position, name);
        return switch (name) {
            case "round" -> round(args, position);
            case "abs" -> fixed(args, position, NUMBER, List.of(NUMBER));
            case "min", "max" -> extreme(args, position);
            case "if" -> choice(args, position);
            case "coalesce" -> coalesce(args, position);
            case "isEmpty" -> {
                arity(args, position, 1, 1);
                yield BOOL;
            }
            case "len" -> fixed(args, position, NUMBER, List.of(TEXT));
            case "upper", "lower", "trim" -> fixed(args, position, TEXT, List.of(TEXT));
            case "contains" -> fixed(args, position, BOOL, List.of(TEXT, TEXT));
            case "today" -> fixed(args, position, DATE, List.of());
            case "now" -> fixed(args, position, DATETIME, List.of());
            case "date" -> fixed(args, position, DATE, List.of(NUMBER, NUMBER, NUMBER));
            case "year", "month", "day", "weekday" -> moment(args, position, Set.of(DATE, DATETIME));
            case "hour", "minute" -> moment(args, position, Set.of(TIME, DATETIME));
            case "dateOf" -> fixed(args, position, DATE, List.of(DATETIME));
            case "addDays", "addMonths" -> fixed(args, position, DATE, List.of(DATE, NUMBER));
            case "sum" -> aggregate(args, position, Set.of(FormulaType.NUMBERS));
            case "count" -> aggregate(args, position, Set.of(FormulaType.NUMBERS, FormulaType.ROWS));
            default -> fixed(args, position, NUMBER, List.of(DATE, DATE));
        };
    }

    private static FormulaType round(List<FormulaNode> args, int position) {
        arity(args, position, 1, 2);
        accept(args.getFirst(), NUMBER, position);
        if (args.size() == 2) {
            FormulaNode digits = args.get(1);
            BigDecimal value = FormulaNode.LITERAL.equals(digits.kind()) && digits.value() != null
                    ? new BigDecimal(digits.value())
                    : null;
            if (value == null
                    || digits.formulaType() != NUMBER
                    || value.scale() > 0
                    || value.signum() < 0
                    || value.compareTo(BigDecimal.valueOf(Formula.MAX_SCALE)) > 0) {
                throw new FormulaException("argument", position, "round");
            }
        }
        return NUMBER;
    }

    private static FormulaType extreme(List<FormulaNode> args, int position) {
        arity(args, position, 2, Formula.MAX_ARGS);
        FormulaType type = joined(args, position);
        if (!type.ordered() && type != FormulaType.NULL) throw new FormulaException("type", position);
        return type;
    }

    private static FormulaType choice(List<FormulaNode> args, int position) {
        arity(args, position, 3, 3);
        accept(args.getFirst(), BOOL, position);
        return joined(args.subList(1, 3), position);
    }

    private static FormulaType coalesce(List<FormulaNode> args, int position) {
        arity(args, position, 2, Formula.MAX_ARGS);
        return joined(args, position);
    }

    private static FormulaType moment(List<FormulaNode> args, int position, Set<FormulaType> accepted) {
        arity(args, position, 1, 1);
        FormulaType type = args.getFirst().formulaType();
        if (type != FormulaType.NULL && !accepted.contains(type)) throw new FormulaException("type", position);
        return NUMBER;
    }

    /** {@code sum} of a column of numbers, {@code count} of rows or of the filled values of a column. */
    private static FormulaType aggregate(List<FormulaNode> args, int position, Set<FormulaType> accepted) {
        arity(args, position, 1, 1);
        if (!accepted.contains(args.getFirst().formulaType())) throw new FormulaException("type", position);
        return NUMBER;
    }

    private static FormulaType fixed(
            List<FormulaNode> args, int position, FormulaType result, List<FormulaType> expected) {
        arity(args, position, expected.size(), expected.size());
        for (int index = 0; index < expected.size(); index++) accept(args.get(index), expected.get(index), position);
        return result;
    }

    private static FormulaType joined(List<FormulaNode> args, int position) {
        FormulaType type = FormulaType.NULL;
        for (FormulaNode arg : args) {
            FormulaType next = FormulaType.join(type, arg.formulaType());
            if (next == null) throw new FormulaException("type", position);
            type = next;
        }
        return type;
    }

    private static void accept(FormulaNode arg, FormulaType expected, int position) {
        FormulaType type = arg.formulaType();
        boolean fits = type == expected || type == FormulaType.NULL || (expected == TEXT && type == FormulaType.OPTION);
        if (!fits) throw new FormulaException("type", position);
    }

    private static void arity(List<FormulaNode> args, int position, int min, int max) {
        if (args.size() < min || args.size() > max) throw new FormulaException("arity", position);
    }

    /** Whether a value is empty: null, or a text of only spaces, tabs and line breaks. */
    static boolean empty(@Nullable Object value) {
        return value == null || (value instanceof String text && FormulaValues.blank(text));
    }
}
