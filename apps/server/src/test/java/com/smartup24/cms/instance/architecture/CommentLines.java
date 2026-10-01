package com.smartup24.cms.instance.architecture;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * Counts the lines of a source whose comment part has Cyrillic letters (plan 10/10, item 3.14, CommentLanguageTest).
 * String literals are skipped: messages and SQL values keep whatever language they need.
 */
final class CommentLines {

    private CommentLines() {}

    /** Java: line and block comments, outside string, char and text block literals. */
    static int java(String source) {
        int[] lineStarts = lineStarts(source);
        Set<Integer> lines = new TreeSet<>();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                while (end > 0 && source.charAt(end - 1) == '\\') {
                    end = source.indexOf("\"\"\"", end + 1);
                }
                i = end < 0 ? length : end + 3;
            } else if (c == '"' || c == '\'') {
                i = afterQuoted(source, i, c);
            } else if (source.startsWith("//", i)) {
                i = collect(source, i, lineEnd(source, i), lineStarts, lines);
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = collect(source, i, end < 0 ? length : end + 2, lineStarts, lines);
            } else {
                i++;
            }
        }
        return lines.size();
    }

    /** SQL: {@code --} and block comments, outside single-quoted literals (a doubled quote stays inside). */
    static int sql(String source) {
        int[] lineStarts = lineStarts(source);
        Set<Integer> lines = new TreeSet<>();
        int i = 0;
        int length = source.length();
        while (i < length) {
            if (source.charAt(i) == '\'') {
                int end = source.indexOf('\'', i + 1);
                while (end > 0 && end + 1 < length && source.charAt(end + 1) == '\'') {
                    end = source.indexOf('\'', end + 2);
                }
                i = end < 0 ? length : end + 1;
            } else if (source.startsWith("--", i)) {
                i = collect(source, i, lineEnd(source, i), lineStarts, lines);
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = collect(source, i, end < 0 ? length : end + 2, lineStarts, lines);
            } else {
                i++;
            }
        }
        return lines.size();
    }

    /** YAML: a {@code #} at the start of a line or after a blank, outside quoted scalars. */
    static int yaml(String source) {
        int count = 0;
        for (String line : source.split("\n", -1)) {
            char quote = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quote != 0) {
                    quote = c == quote ? 0 : quote;
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                    if (cyrillic(line, i, line.length())) {
                        count++;
                    }
                    break;
                }
            }
        }
        return count;
    }

    private static int lineEnd(String source, int from) {
        int end = source.indexOf('\n', from);
        return end < 0 ? source.length() : end;
    }

    private static int[] lineStarts(String source) {
        int[] starts = new int[16];
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }
        return Arrays.copyOf(starts, count);
    }

    private static int afterQuoted(String source, int start, char quote) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote || c == '\n') {
                return i + 1;
            } else {
                i++;
            }
        }
        return i;
    }

    /** Adds the line of every Cyrillic letter in {@code [from, to)}; returns {@code to}. */
    private static int collect(String source, int from, int to, int[] lineStarts, Set<Integer> lines) {
        for (int i = from; i < to; i++) {
            if (Character.UnicodeScript.of(source.charAt(i)) == Character.UnicodeScript.CYRILLIC) {
                int line = Arrays.binarySearch(lineStarts, i);
                lines.add(line >= 0 ? line : -line - 2);
            }
        }
        return to;
    }

    private static boolean cyrillic(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.CYRILLIC) {
                return true;
            }
        }
        return false;
    }
}
