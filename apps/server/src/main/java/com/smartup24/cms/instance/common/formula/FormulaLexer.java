package com.smartup24.cms.instance.common.formula;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits a formula into tokens (ADR-0042): numbers ({@code 12}, {@code 0.5}), texts in double or single quotes with the
 * escapes {@code \"}, {@code \'}, {@code \\}, {@code \n} and {@code \t}, names, the operators and parentheses. The
 * typographic {@code × ÷ − ≠ ≤ ≥} and {@code <>} are read as {@code * / - != <= >= !=}. Spaces, tabs and line breaks
 * separate tokens; any other character is a {@code syntax} problem at its position.
 */
final class FormulaLexer {
    enum Kind {
        NUMBER,
        TEXT,
        NAME,
        OPERATOR,
        OPEN,
        CLOSE,
        COMMA,
        END
    }

    record Token(Kind kind, String text, int position) {}

    private static final Map<Character, String> TYPOGRAPHIC =
            Map.of('×', "*", '÷', "/", '−', "-", '≠', "!=", '≤', "<=", '≥', ">=");

    private final String source;
    private int at;

    private FormulaLexer(String source) {
        this.source = source;
    }

    /** The tokens of {@code source}, the last of them {@link Kind#END}. */
    static List<Token> tokens(String source) {
        FormulaLexer lexer = new FormulaLexer(source);
        List<Token> tokens = new ArrayList<>();
        Token token;
        do {
            token = lexer.next();
            tokens.add(token);
        } while (token.kind() != Kind.END);
        return tokens;
    }

    private Token next() {
        while (at < source.length() && " \t\r\n".indexOf(source.charAt(at)) >= 0) at++;
        int start = at;
        if (at >= source.length()) return new Token(Kind.END, "", start + 1);
        char c = source.charAt(at);
        if (c >= '0' && c <= '9') return number(start);
        if (c == '"' || c == '\'') return text(start, c);
        if (letter(c)) {
            while (at < source.length() && (letter(source.charAt(at)) || digit(source.charAt(at)))) at++;
            return new Token(Kind.NAME, source.substring(start, at), start + 1);
        }
        at++;
        switch (c) {
            case '(' -> {
                return new Token(Kind.OPEN, "(", start + 1);
            }
            case ')' -> {
                return new Token(Kind.CLOSE, ")", start + 1);
            }
            case ',' -> {
                return new Token(Kind.COMMA, ",", start + 1);
            }
            case '+', '-', '*', '/', '&', '=' -> {
                return new Token(Kind.OPERATOR, String.valueOf(c), start + 1);
            }
            case '!' -> {
                if (take('=')) return new Token(Kind.OPERATOR, "!=", start + 1);
                throw new FormulaException("syntax", start + 1);
            }
            case '<' -> {
                if (take('=')) return new Token(Kind.OPERATOR, "<=", start + 1);
                if (take('>')) return new Token(Kind.OPERATOR, "!=", start + 1);
                return new Token(Kind.OPERATOR, "<", start + 1);
            }
            case '>' -> {
                return new Token(Kind.OPERATOR, take('=') ? ">=" : ">", start + 1);
            }
            default -> {
                String typographic = TYPOGRAPHIC.get(c);
                if (typographic == null) throw new FormulaException("syntax", start + 1);
                return new Token(Kind.OPERATOR, typographic, start + 1);
            }
        }
    }

    private Token number(int start) {
        while (at < source.length() && digit(source.charAt(at))) at++;
        if (at + 1 < source.length() && source.charAt(at) == '.' && digit(source.charAt(at + 1))) {
            at++;
            while (at < source.length() && digit(source.charAt(at))) at++;
        }
        if (at < source.length() && letter(source.charAt(at))) throw new FormulaException("syntax", at + 1);
        return new Token(Kind.NUMBER, source.substring(start, at), start + 1);
    }

    private Token text(int start, char quote) {
        at++;
        StringBuilder text = new StringBuilder();
        while (at < source.length()) {
            char c = source.charAt(at++);
            if (c == quote) return new Token(Kind.TEXT, text.toString(), start + 1);
            if (c != '\\') {
                text.append(c);
                continue;
            }
            if (at >= source.length()) break;
            char escaped = source.charAt(at++);
            switch (escaped) {
                case '"', '\'', '\\' -> text.append(escaped);
                case 'n' -> text.append('\n');
                case 't' -> text.append('\t');
                default -> throw new FormulaException("syntax", at - 1);
            }
        }
        throw new FormulaException("syntax", start + 1);
    }

    private boolean take(char expected) {
        if (at < source.length() && source.charAt(at) == expected) {
            at++;
            return true;
        }
        return false;
    }

    private static boolean letter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }
}
