package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 3.11: no error is swallowed. A {@code catch} in main code rethrows, logs, or uses what it caught
 * (returns it as a result, records it in a status). Only an exception that is an expected outcome of reading a value —
 * a number or date that does not parse, a key that is not there, a client that went away — may be turned into a
 * result without a trace: those types are listed here. Anything else silently dropped hides a failure.
 */
class NoSwallowedErrorsTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern CATCH =
            Pattern.compile("catch\\s*\\(\\s*(?:final\\s+)?([\\w.|\\s]+?)\\s+(\\w+)\\s*\\)\\s*\\{");

    /** Exceptions that are an answer about a value, not a failure of the system. */
    private static final Set<String> OUTCOMES = Set.of(
            "NumberFormatException",
            "DateTimeParseException",
            "PatternSyntaxException",
            "ArithmeticException",
            "IllegalArgumentException",
            "NoSuchKeyException",
            "HttpClientErrorException.NotFound",
            "ClientAbortException",
            "TimeoutException");

    @Test
    @DisplayName("3.11: every catch in main code rethrows, logs or uses what it caught")
    void noErrorIsSwallowed() throws IOException {
        TreeSet<String> swallowed = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String name = MAIN.relativize(file).toString().replace('\\', '/');
                Matcher handler = CATCH.matcher(source);
                while (handler.find()) {
                    String body = withoutComments(block(source, handler.end() - 1));
                    if (!handles(body, handler.group(2)) && !onlyOutcomes(handler.group(1))) {
                        int line = source.substring(0, handler.start()).split("\n", -1).length;
                        swallowed.add(name + ":" + line + " catch ("
                                + handler.group(1).strip() + ")");
                    }
                }
            }
        }
        assertThat(swallowed)
                .as("catch blocks that drop an error without a trace: rethrow, log, or use it")
                .isEmpty();
    }

    private static boolean handles(String body, String variable) {
        return body.contains("throw")
                || Pattern.compile("\\blog\\.\\w+\\(").matcher(body).find()
                || Pattern.compile("\\b" + Pattern.quote(variable) + "\\b")
                        .matcher(body)
                        .find()
                || body.contains("interrupt()");
    }

    private static boolean onlyOutcomes(String types) {
        List<String> caught =
                Arrays.stream(types.split("\\|")).map(String::strip).toList();
        return caught.stream().allMatch(OUTCOMES::contains);
    }

    private static String withoutComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String block(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open + 1, i);
            }
        }
        return "";
    }
}
