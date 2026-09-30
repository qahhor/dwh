package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 3.14: code comments are written in one language, English (CODE_STYLE, section 2.1.2). The Java
 * sources of the server and of the libraries are checked, and the server's application*.yml and SQL migrations.
 * {@code comment-language-baseline.txt} holds, per file that still has Russian comments, the number of its comment
 * lines with Cyrillic letters; the numbers only go down. A new file with a Russian comment fails, a listed file whose
 * number grows fails, and a file whose number drops fails too until the baseline is lowered (a ratchet). String
 * literals and text blocks are not comments: messages and SQL keep whatever language they need.
 *
 * <p>After translating comments, rewrite the baseline with {@code -Dcomment.baseline.update=true}: it lowers numbers
 * and drops translated files, and never raises a number or adds a file.
 */
class CommentLanguageTest {

    /** The repository root, seen from the server module where the tests run. */
    private static final Path REPOSITORY = Path.of("../..");

    private static final Path BASELINE = Path.of("src/test/resources/comment-language-baseline.txt");
    private static final String HEADER = """
            # Files that still have comments in Russian, with the number of their comment lines that have
            # Cyrillic letters (plan 10/10, item 3.14, checked by CommentLanguageTest). The numbers only go down:
            # translate comments and lower the number (-Dcomment.baseline.update=true); a new file never goes here.
            """;

    @Test
    @DisplayName("3.14: no file gains Russian comments, and translated comments lower the baseline")
    void commentsAreInEnglish() throws IOException {
        Map<String, Integer> found = offenders();
        Map<String, Integer> baseline = baseline();
        if (Boolean.getBoolean("comment.baseline.update")) {
            baseline = lowered(found, baseline);
            write(baseline);
        }

        TreeSet<String> added = new TreeSet<>();
        TreeSet<String> grown = new TreeSet<>();
        for (Map.Entry<String, Integer> file : found.entrySet()) {
            Integer listed = baseline.get(file.getKey());
            if (listed == null) {
                added.add(file.getKey() + " " + file.getValue());
            } else if (file.getValue() > listed) {
                grown.add(file.getKey() + " " + listed + " -> " + file.getValue());
            }
        }
        TreeSet<String> dropped = new TreeSet<>();
        for (Map.Entry<String, Integer> file : baseline.entrySet()) {
            int now = found.getOrDefault(file.getKey(), 0);
            if (now < file.getValue()) {
                dropped.add(file.getKey() + " " + file.getValue() + " -> " + now);
            }
        }

        assertThat(added)
                .as("files with comments in Russian: write comments in English (CODE_STYLE, section 2.1.2)")
                .isEmpty();
        assertThat(grown)
                .as("files with more Russian comment lines than %s allows: write comments in English", BASELINE)
                .isEmpty();
        assertThat(dropped)
                .as("fewer Russian comment lines than %s says: lower it with -Dcomment.baseline.update=true", BASELINE)
                .isEmpty();
    }

    @Test
    @DisplayName("3.14: the baseline update lowers numbers and drops files, never raises or adds")
    void updateNeverRaises() {
        Map<String, Integer> found = Map.of("a.java", 5, "b.java", 1, "new.java", 3);
        Map<String, Integer> baseline = Map.of("a.java", 3, "b.java", 4, "gone.java", 2);

        assertThat(lowered(found, baseline)).containsExactly(Map.entry("a.java", 3), Map.entry("b.java", 1));
    }

    @Test
    @DisplayName("3.14: SQL and YAML comments are told from literals and values")
    void sqlAndYamlComments() {
        String cyrillic = String.valueOf((char) 0x0416);
        assertThat(CommentLines.sql("select '" + cyrillic + " -- x';\n-- " + cyrillic + "\n/* " + cyrillic + " */"))
                .isEqualTo(2);
        assertThat(CommentLines.sql("insert into t values ('it''s " + cyrillic + "'); -- plain"))
                .isZero();
        assertThat(CommentLines.yaml("# " + cyrillic + "\nkey: \"" + cyrillic + " # no\"\nurl: a#" + cyrillic
                        + "\nx: 1 # " + cyrillic))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("3.14: the server and every library are scanned, main and test sources")
    void librariesAreScanned() throws IOException {
        assertThat(roots())
                .map(root -> REPOSITORY.relativize(root).toString().replace('\\', '/'))
                .contains(
                        "apps/server/src/main/java",
                        "apps/server/src/test/java",
                        "libs/core-types/src/main/java",
                        "libs/platform-common/src/test/java");
    }

    @Test
    @DisplayName("3.14: comments are told from string literals, text blocks and char literals")
    void commentsAreToldFromLiterals() {
        String cyrillic = String.valueOf((char) 0x0416);
        assertThat(CommentLines.java("String s = \"" + cyrillic + "\"; // plain"))
                .isZero();
        assertThat(CommentLines.java("String s = \"\"\"\n  " + cyrillic + " // not a comment\n  \"\"\";"))
                .isZero();
        assertThat(CommentLines.java("char c = '\"'; String s = \"/* \\\" */\"; int x; // " + cyrillic))
                .isOne();
        assertThat(CommentLines.java("/** " + cyrillic + " */ class A {}")).isOne();
        assertThat(CommentLines.java("String url = \"http://x\"; /* ok */")).isZero();
    }

    @Test
    @DisplayName("3.14: lines are counted, not comments: one more Russian line in a comment is one more")
    void linesAreCounted() {
        String cyrillic = String.valueOf((char) 0x0416);
        String block = "/**\n * " + cyrillic + "\n * english\n * " + cyrillic + cyrillic + "\n */\n";
        assertThat(CommentLines.java(block)).isEqualTo(2);
        assertThat(CommentLines.java(block + "int x; // " + cyrillic + "\n")).isEqualTo(3);
        assertThat(CommentLines.java("/* " + cyrillic + " */ int x; // " + cyrillic + "\n"))
                .isOne();
    }

    private static Map<String, Integer> offenders() throws IOException {
        TreeMap<String, Integer> found = new TreeMap<>();
        List<Path> sources = new ArrayList<>();
        for (Path root : roots()) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        sources.addAll(resources());
        for (Path file : sources) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            int lines = name.endsWith(".java")
                    ? CommentLines.java(text)
                    : name.endsWith(".sql") ? CommentLines.sql(text) : CommentLines.yaml(text);
            if (lines > 0) {
                found.put(REPOSITORY.relativize(file).toString().replace('\\', '/'), lines);
            }
        }
        return found;
    }

    /** The server's configuration (application*.yml) and its migrations (db/**.sql). */
    private static List<Path> resources() throws IOException {
        Path resources = REPOSITORY.resolve("apps/server/src/main/resources");
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(resources)) {
            files.filter(path -> path.getFileName().toString().matches("application.*\\.ya?ml"))
                    .sorted()
                    .forEach(found::add);
        }
        try (Stream<Path> files = Files.walk(resources.resolve("db"))) {
            files.filter(path -> path.toString().endsWith(".sql")).sorted().forEach(found::add);
        }
        return found;
    }

    /** The Java sources of the server and of every library, main and test. */
    private static List<Path> roots() throws IOException {
        List<Path> modules = new ArrayList<>(List.of(REPOSITORY.resolve("apps/server")));
        try (Stream<Path> libraries = Files.list(REPOSITORY.resolve("libs"))) {
            libraries.filter(Files::isDirectory).sorted().forEach(modules::add);
        }
        List<Path> roots = new ArrayList<>();
        for (Path module : modules) {
            for (String source : List.of("src/main/java", "src/test/java")) {
                Path root = module.resolve(source);
                if (Files.isDirectory(root)) {
                    roots.add(root);
                }
            }
        }
        return roots;
    }

    private static Map<String, Integer> baseline() throws IOException {
        TreeMap<String, Integer> listed = new TreeMap<>();
        for (String line : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                int space = entry.lastIndexOf(' ');
                listed.put(entry.substring(0, space), Integer.parseInt(entry.substring(space + 1)));
            }
        }
        return listed;
    }

    /** The baseline after an update: each listed file at its current number if lower; nothing added or raised. */
    static Map<String, Integer> lowered(Map<String, Integer> found, Map<String, Integer> baseline) {
        TreeMap<String, Integer> kept = new TreeMap<>();
        baseline.forEach((file, listed) -> {
            int now = found.getOrDefault(file, 0);
            if (now > 0) {
                kept.put(file, Math.min(now, listed));
            }
        });
        return kept;
    }

    private static void write(Map<String, Integer> baseline) throws IOException {
        StringBuilder text = new StringBuilder(HEADER);
        baseline.forEach(
                (file, lines) -> text.append(file).append(' ').append(lines).append('\n'));
        Files.writeString(BASELINE, text.toString(), StandardCharsets.UTF_8);
    }
}
