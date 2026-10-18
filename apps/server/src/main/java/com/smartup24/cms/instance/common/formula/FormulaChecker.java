package com.smartup24.cms.instance.common.formula;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the types of a parse tree against the fields a formula may read (ADR-0042) and gives the checked nodes. A
 * field the formula names must be one of them ({@code unknown_field}); an operator or a function must get the types it
 * takes ({@code type}, {@code arity}, {@code argument}). The arithmetic takes numbers, {@code &} joins any values as
 * text, {@code =}/{@code !=} compare one type (a text with a code, a key with a number), the order comparisons take
 * numbers, texts, dates, moments and times, {@code and}/{@code or}/{@code not} take yes/no values.
 */
final class FormulaChecker {
    private static final Set<String> ARITHMETIC = Set.of("+", "-", "*", "/");
    private static final Set<String> ORDER = Set.of("<", "<=", ">", ">=");

    private final Map<String, FormulaType> fields;
    private final Set<String> read = new LinkedHashSet<>();

    private FormulaChecker(Map<String, FormulaType> fields) {
        this.fields = fields;
    }

    /** The checked root of {@code ast} and the keys of the fields it reads, in the order it reads them. */
    record Checked(FormulaNode root, Set<String> fields) {}

    static Checked check(FormulaAst ast, Map<String, FormulaType> fields) {
        FormulaChecker checker = new FormulaChecker(fields);
        FormulaNode root = checker.node(ast);
        return new Checked(root, Set.copyOf(checker.read));
    }

    private FormulaNode node(FormulaAst ast) {
        return switch (ast) {
            case FormulaAst.Literal literal -> FormulaNode.literal(literal.type(), literal.value());
            case FormulaAst.Name name -> field(name);
            case FormulaAst.Unary unary -> unary(unary);
            case FormulaAst.Binary binary -> binary(binary);
            case FormulaAst.Call call -> call(call);
        };
    }

    private FormulaNode field(FormulaAst.Name name) {
        FormulaType type = fields.get(name.name());
        if (type == null) throw new FormulaException("unknown_field", name.position(), name.name());
        read.add(name.name());
        return FormulaNode.field(name.name(), type);
    }

    private FormulaNode unary(FormulaAst.Unary unary) {
        FormulaNode operand = node(unary.operand());
        FormulaType expected = "not".equals(unary.operator()) ? FormulaType.BOOL : FormulaType.NUMBER;
        require(operand.formulaType(), expected, unary.position());
        return FormulaNode.of(FormulaNode.UNARY, unary.operator(), expected, List.of(operand));
    }

    private FormulaNode binary(FormulaAst.Binary binary) {
        FormulaNode left = node(binary.left());
        FormulaNode right = node(binary.right());
        String operator = binary.operator();
        FormulaType a = left.formulaType();
        FormulaType b = right.formulaType();
        int position = binary.position();
        FormulaType result;
        if (ARITHMETIC.contains(operator)) {
            require(a, FormulaType.NUMBER, position);
            require(b, FormulaType.NUMBER, position);
            result = FormulaType.NUMBER;
        } else if ("&".equals(operator)) {
            result = FormulaType.TEXT;
        } else if ("and".equals(operator) || "or".equals(operator)) {
            require(a, FormulaType.BOOL, position);
            require(b, FormulaType.BOOL, position);
            result = FormulaType.BOOL;
        } else {
            boolean fits = ORDER.contains(operator)
                    ? FormulaType.join(a, b) != null && orderedOrNull(a) && orderedOrNull(b)
                    : FormulaType.comparable(a, b);
            if (!fits) throw new FormulaException("type", position);
            result = FormulaType.BOOL;
        }
        return FormulaNode.of(FormulaNode.BINARY, operator, result, List.of(left, right));
    }

    private FormulaNode call(FormulaAst.Call call) {
        List<FormulaNode> args = new ArrayList<>();
        for (FormulaAst arg : call.args()) args.add(node(arg));
        FormulaType type = FormulaFunctions.type(call.function(), args, call.position());
        return FormulaNode.of(FormulaNode.CALL, call.function(), type, args);
    }

    private static boolean orderedOrNull(FormulaType type) {
        return type == FormulaType.NULL || type.ordered();
    }

    private static void require(FormulaType actual, FormulaType expected, int position) {
        if (actual != expected && actual != FormulaType.NULL) throw new FormulaException("type", position);
    }
}
