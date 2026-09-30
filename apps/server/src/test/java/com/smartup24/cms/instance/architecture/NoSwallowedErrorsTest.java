package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 3.11: no error is swallowed. A {@code catch} in main code — of the server and of the libraries —
 * rethrows, logs, or uses what it caught (returns it as a result, records it in a status). Only an exception that is
 * an expected outcome of reading a value — a number or date that does not parse, a key that is not there, a client
 * that went away — may be turned into a result without a trace: those types are listed here. A broader type is
 * allowed at a named site only, with its reason. A catch of {@code Exception}, {@code RuntimeException} or
 * {@code Throwable} catches failures nobody foresaw, so a log line at debug or trace level does not count for it: it
 * rethrows, uses the exception, or logs at warn or error level.
 */
class NoSwallowedErrorsTest {

    private static final List<Root> ROOTS = roots();

    private static final Pattern CATCH =
            Pattern.compile("catch\\s*\\(\\s*(?:final\\s+)?([\\w.|\\s]+?)\\s+(\\w+)\\s*\\)\\s*\\{");

    private static final Pattern ANY_LOG = Pattern.compile("\\b(?:log|logger|LOG|LOGGER)\\.\\w+\\(");
    private static final Pattern LOUD_LOG = Pattern.compile("\\b(?:log|logger|LOG|LOGGER)\\.(?:warn|error)\\(");
    /** A debug or trace call, up to its closing parenthesis and semicolon (no nested statements inside). */
    private static final Pattern QUIET_LOG =
            Pattern.compile("\\b(?:log|logger|LOG|LOGGER)\\.(?:debug|trace)\\((?s:.*?)\\);");

    /** Exceptions that are an answer about a value, not a failure of the system. */
    private static final Set<String> OUTCOMES = Set.of(
            "NumberFormatException",
            "DateTimeParseException",
            "PatternSyntaxException",
            "ArithmeticException",
            "NoSuchKeyException",
            "HttpClientErrorException.NotFound",
            "ClientAbortException");

    private static final Set<String> BROAD = Set.of("Exception", "RuntimeException", "Throwable");

    /**
     * Sites where a type outside {@link #OUTCOMES} is an expected answer about a value, keyed by file and caught type,
     * with the reason.
     */
    private static final Map<String, String> EXPECTED_AT = Map.of(
            "com/smartup24/cms/instance/common/health/ReadinessChecks.java catch (TimeoutException)",
            "a check that outlives its deadline is the answer: DOWN, with the timeout as the reason",
            "com/smartup24/cms/instance/common/query/QueryCompiler.java catch (IllegalArgumentException)",
            "a filter value that does not parse for its field makes the filter invalid; the caller answers 400",
            "com/smartup24/cms/instance/config/idempotency/IdempotencyFilter.java catch (IllegalArgumentException)",
            "an Idempotency-Key that is not a UUID is answered 400 right there",
            "libs/core-types/com/smartup24/cms/core/pagination/CursorUtils.java catch (IllegalArgumentException)",
            "a cursor that is not Base64 decodes to null; the caller answers INVALID_CURSOR");

    @Test
    @DisplayName("3.11: every catch in main code rethrows, logs or uses what it caught")
    void noErrorIsSwallowed() throws IOException {
        TreeSet<String> swallowed = new TreeSet<>();
        for (Root root : ROOTS) {
            for (Site site : sites(root)) {
                if (!handles(site) && !expected(site)) {
                    swallowed.add(site.file() + ":" + site.line() + " catch (" + site.types() + ")");
                }
            }
        }
        assertThat(swallowed)
                .as("catch blocks that drop an error without a trace: rethrow, log, or use it")
                .isEmpty();
    }

    @Test
    @DisplayName("3.11: every listed site still has its catch")
    void listedSitesExist() throws IOException {
        TreeSet<String> present = new TreeSet<>();
        for (Root root : ROOTS) {
            for (Site site : sites(root)) {
                present.add(site.file() + " catch (" + site.types() + ")");
            }
        }
        assertThat(present).containsAll(EXPECTED_AT.keySet());
    }

    @Test
    @DisplayName("3.11: a broad catch that only logs at debug level is a swallowed error")
    void broadCatchNeedsALoudTrace() {
        Site quiet = new Site("A.java", 1, "Exception", "failure", " log.debug(\"x {}\", failure.getMessage()); ");
        Site loud = new Site("A.java", 1, "Exception", "failure", " log.warn(\"x\", failure); ");
        Site used = new Site("A.java", 1, "RuntimeException", "failure", " return Result.failed(failure); ");
        Site narrow = new Site("A.java", 1, "IOException", "failure", " log.debug(\"x\", failure); ");

        assertThat(handles(quiet)).isFalse();
        assertThat(handles(loud)).isTrue();
        assertThat(handles(used)).isTrue();
        assertThat(handles(narrow)).isTrue();
        assertThat(expected(new Site("A.java", 1, "IllegalArgumentException", "e", "")))
                .isFalse();
        assertThat(expected(new Site("A.java", 1, "TimeoutException", "e", ""))).isFalse();
    }

    /** One catch: the file, its line, the caught types as written, the variable and the block without comments. */
    private record Site(String file, int line, String types, String variable, String body) {}

    private static List<Site> sites(Root root) throws IOException {
        List<Site> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.path())) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String name =
                        root.prefix() + root.path().relativize(file).toString().replace('\\', '/');
                Matcher handler = CATCH.matcher(source);
                while (handler.find()) {
                    int line = source.substring(0, handler.start()).split("\n", -1).length;
                    found.add(new Site(
                            name,
                            line,
                            handler.group(1).strip().replaceAll("\\s+", " "),
                            handler.group(2),
                            withoutComments(block(source, handler.end() - 1))));
                }
            }
        }
        return found;
    }

    private static boolean handles(Site site) {
        boolean broad = caught(site.types()).stream().anyMatch(BROAD::contains);
        String body = broad ? QUIET_LOG.matcher(site.body()).replaceAll("") : site.body();
        return body.contains("throw")
                || (broad ? LOUD_LOG : ANY_LOG).matcher(body).find()
                || Pattern.compile("\\b" + Pattern.quote(site.variable()) + "\\b")
                        .matcher(body)
                        .find()
                || body.contains("interrupt()");
    }

    private static boolean expected(Site site) {
        return caught(site.types()).stream().allMatch(OUTCOMES::contains)
                || EXPECTED_AT.containsKey(site.file() + " catch (" + site.types() + ")");
    }

    private static List<String> caught(String types) {
        return Arrays.stream(types.split("\\|")).map(String::strip).toList();
    }

    /** A source root and the prefix its files are named with. */
    private record Root(Path path, String prefix) {}

    /** The server's main code, then each library's: tests run in {@code apps/server}. */
    private static List<Root> roots() {
        List<Root> roots = new ArrayList<>(List.of(new Root(Path.of("src/main/java"), "")));
        Path libs = Path.of("../../libs");
        assertThat(libs).as("the libraries next to the server").isDirectory();
        try (Stream<Path> modules = Files.list(libs)) {
            modules.filter(module -> Files.isDirectory(module.resolve("src/main/java")))
                    .sorted()
                    .forEach(module ->
                            roots.add(new Root(module.resolve("src/main/java"), "libs/" + module.getFileName() + "/")));
        } catch (IOException unreadable) {
            throw new IllegalStateException("libs are not readable", unreadable);
        }
        return List.copyOf(roots);
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
