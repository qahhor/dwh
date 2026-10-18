package com.smartup24.cms.instance.common.formula;

import com.smartup24.cms.instance.common.formula.FormulaLexer.Kind;
import com.smartup24.cms.instance.common.formula.FormulaLexer.Token;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the tokens of a formula (ADR-0042), lowest precedence first:
 *
 * <pre>
 * expression := or
 * or         := and ("or" and)*
 * and        := not ("and" not)*
 * not        := "not" not | comparison
 * comparison := concat (("=" | "!=" | "&lt;" | "&lt;=" | "&gt;" | "&gt;=") concat)?   -- not chained
 * concat     := additive ("&amp;" additive)*
 * additive   := term (("+" | "-") term)*
 * term       := unary (("*" | "/") unary)*
 * unary      := "-" unary | primary
 * primary    := number | text | "true" | "false" | "null" | field | name "(" arguments? ")" | "(" expression ")"
 * </pre>
 *
 * The bounds are checked as it goes — the length of the text, the nodes, the nesting, the arguments of a call and the
 * size of a literal — so a formula never grows the stack or the work without limit.
 */
final class FormulaParser {
    private static final Set<String> COMPARISONS = Set.of("=", "!=", "<", "<=", ">", ">=");
    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "true", "false", "null");

    private final List<Token> tokens;
    private int at;
    private int nodes;
    private int depth;

    private FormulaParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** The parse tree of {@code source}, or a {@link FormulaException} naming the first problem and its position. */
    static FormulaAst parse(String source) {
        if (source.isBlank()) throw new FormulaException("syntax", 1);
        if (source.length() > Formula.MAX_LENGTH) throw new FormulaException("limit", Formula.MAX_LENGTH + 1);
        FormulaParser parser = new FormulaParser(FormulaLexer.tokens(source));
        FormulaAst root = parser.or();
        Token rest = parser.peek();
        if (rest.kind() != Kind.END) throw new FormulaException("syntax", rest.position());
        return root;
    }

    private FormulaAst or() {
        FormulaAst left = and();
        while (keyword("or")) {
            Token operator = advance();
            left = node(new FormulaAst.Binary("or", left, and(), operator.position()));
        }
        return left;
    }

    private FormulaAst and() {
        FormulaAst left = not();
        while (keyword("and")) {
            Token operator = advance();
            left = node(new FormulaAst.Binary("and", left, not(), operator.position()));
        }
        return left;
    }

    private FormulaAst not() {
        if (!keyword("not")) return comparison();
        Token operator = advance();
        enter(operator);
        FormulaAst operand = not();
        depth--;
        return node(new FormulaAst.Unary("not", operand, operator.position()));
    }

    private FormulaAst comparison() {
        FormulaAst left = concat();
        if (!operator(COMPARISONS)) return left;
        Token operator = advance();
        FormulaAst result = node(new FormulaAst.Binary(operator.text(), left, concat(), operator.position()));
        if (operator(COMPARISONS)) throw new FormulaException("syntax", peek().position());
        return result;
    }

    private FormulaAst concat() {
        FormulaAst left = additive();
        while (operator(Set.of("&"))) {
            Token operator = advance();
            left = node(new FormulaAst.Binary("&", left, additive(), operator.position()));
        }
        return left;
    }

    private FormulaAst additive() {
        FormulaAst left = term();
        while (operator(Set.of("+", "-"))) {
            Token operator = advance();
            left = node(new FormulaAst.Binary(operator.text(), left, term(), operator.position()));
        }
        return left;
    }

    private FormulaAst term() {
        FormulaAst left = unary();
        while (operator(Set.of("*", "/"))) {
            Token operator = advance();
            left = node(new FormulaAst.Binary(operator.text(), left, unary(), operator.position()));
        }
        return left;
    }

    private FormulaAst unary() {
        if (!operator(Set.of("-"))) return primary();
        Token operator = advance();
        enter(operator);
        FormulaAst operand = unary();
        depth--;
        return node(new FormulaAst.Unary("-", operand, operator.position()));
    }

    private FormulaAst primary() {
        Token token = advance();
        switch (token.kind()) {
            case NUMBER -> {
                return node(new FormulaAst.Literal(FormulaType.NUMBER, number(token), token.position()));
            }
            case TEXT -> {
                if (token.text().codePointCount(0, token.text().length()) > Formula.MAX_LITERAL)
                    throw new FormulaException("limit", token.position());
                return node(new FormulaAst.Literal(FormulaType.TEXT, token.text(), token.position()));
            }
            case NAME -> {
                return name(token);
            }
            case OPEN -> {
                enter(token);
                FormulaAst inner = or();
                expect(Kind.CLOSE);
                depth--;
                return inner;
            }
            default -> throw new FormulaException("syntax", token.position());
        }
    }

    private FormulaAst name(Token token) {
        String name = token.text();
        switch (name) {
            case "true", "false" -> {
                return node(new FormulaAst.Literal(FormulaType.BOOL, name, token.position()));
            }
            case "null" -> {
                return node(new FormulaAst.Literal(FormulaType.NULL, null, token.position()));
            }
            default -> {
                if (KEYWORDS.contains(name)) throw new FormulaException("syntax", token.position());
            }
        }
        if (peek().kind() != Kind.OPEN) return node(new FormulaAst.Name(name, token.position()));
        enter(advance());
        List<FormulaAst> args = new ArrayList<>();
        if (peek().kind() != Kind.CLOSE) {
            args.add(or());
            while (peek().kind() == Kind.COMMA) {
                Token comma = advance();
                if (args.size() >= Formula.MAX_ARGS) throw new FormulaException("limit", comma.position());
                args.add(or());
            }
        }
        expect(Kind.CLOSE);
        depth--;
        return node(new FormulaAst.Call(name, args, token.position()));
    }

    /** A number literal as its canonical text, within the bounds of the formula's numbers. */
    private static String number(Token token) {
        BigDecimal value = new BigDecimal(token.text());
        if (value.scale() > Formula.MAX_SCALE
                || value.precision() - value.scale() > FormulaNumbers.MAX_INTEGER_DIGITS) {
            throw new FormulaException("limit", token.position());
        }
        return FormulaNumbers.text(FormulaNumbers.normalize(value));
    }

    private FormulaAst node(FormulaAst node) {
        if (++nodes > Formula.MAX_NODES) throw new FormulaException("limit", node.position());
        return node;
    }

    private void enter(Token token) {
        if (++depth > Formula.MAX_DEPTH) throw new FormulaException("limit", token.position());
    }

    private void expect(Kind kind) {
        Token token = advance();
        if (token.kind() != kind) throw new FormulaException("syntax", token.position());
    }

    private boolean keyword(String word) {
        Token token = peek();
        return token.kind() == Kind.NAME && token.text().equals(word);
    }

    private boolean operator(Set<String> operators) {
        Token token = peek();
        return token.kind() == Kind.OPERATOR && operators.contains(token.text());
    }

    private Token peek() {
        return tokens.get(at);
    }

    private Token advance() {
        Token token = tokens.get(at);
        if (token.kind() != Kind.END) at++;
        return token;
    }
}
