package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 3.14: code comments are written in one language, English (CODE_STYLE, section 2.1.2). The files
 * that still carry Russian comments are listed in {@code comment-language-baseline.txt}; the list only shrinks. A
 * new file with a Russian comment fails, and so does a listed file that has been translated, until its line is
 * removed. String literals and text blocks are not comments: messages and SQL keep whatever language they need.
 *
 * <p>After translating files, rewrite the list with {@code -Dcomment.baseline.update=true}; it never grows that way,
 * because new offenders fail first.
 */
class CommentLanguageTest {

    private static final List<Path> ROOTS = List.of(Path.of("src/main/java"), Path.of("src/test/java"));
    private static final Path BASELINE = Path.of("src/test/resources/comment-language-baseline.txt");
    private static final String HEADER = """
            # Java files that still have comments in Russian (plan 10/10, item 3.14, checked by CommentLanguageTest).
            # The list only shrinks: translate a file and remove its line; a new file never goes here.
            """;

    @Test
    @DisplayName("3.14: no new file has comments in Russian, and translated files leave the baseline")
    void commentsAreInEnglish() throws IOException {
        Set<String> offenders = offenders();
        Set<String> baseline = baseline();
        if (Boolean.getBoolean("comment.baseline.update")) {
            TreeSet<String> kept = new TreeSet<>(offenders);
            kept.retainAll(baseline);
            Files.writeString(BASELINE, HEADER + String.join("\n", kept) + "\n", StandardCharsets.UTF_8);
            baseline = kept;
        }

        TreeSet<String> added = new TreeSet<>(offenders);
        added.removeAll(baseline);
        TreeSet<String> translated = new TreeSet<>(baseline);
        translated.removeAll(offenders);

        assertThat(added)
                .as("files with comments in Russian: write comments in English (CODE_STYLE, section 2.1.2)")
                .isEmpty();
        assertThat(translated)
                .as("translated files still listed in %s: remove their lines", BASELINE)
                .isEmpty();
    }

    @Test
    @DisplayName("3.14: comments are told from string literals, text blocks and char literals")
    void commentsAreToldFromLiterals() {
        String cyrillic = String.valueOf((char) 0x0416);
        assertThat(hasCyrillicComment("String s = \"" + cyrillic + "\"; // plain"))
                .isFalse();
        assertThat(hasCyrillicComment("String s = \"\"\"\n  " + cyrillic + " // not a comment\n  \"\"\";"))
                .isFalse();
        assertThat(hasCyrillicComment("char c = '\"'; String s = \"/* \\\" */\"; int x; // " + cyrillic))
                .isTrue();
        assertThat(hasCyrillicComment("/** " + cyrillic + " */ class A {}")).isTrue();
        assertThat(hasCyrillicComment("String url = \"http://x\"; /* ok */")).isFalse();
    }

    private static Set<String> offenders() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        for (Path root : ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file :
                        files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    if (hasCyrillicComment(Files.readString(file, StandardCharsets.UTF_8))) {
                        found.add(root.getParent().getFileName() + "/"
                                + root.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
        return found;
    }

    private static Set<String> baseline() throws IOException {
        TreeSet<String> listed = new TreeSet<>();
        for (String line : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                listed.add(entry);
            }
        }
        return listed;
    }

    /** Walks the source once, skipping literals, and looks for Cyrillic letters inside comments only. */
    static boolean hasCyrillicComment(String source) {
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
                int end = source.indexOf('\n', i);
                end = end < 0 ? length : end;
                if (cyrillic(source, i, end)) {
                    return true;
                }
                i = end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? length : end + 2;
                if (cyrillic(source, i, end)) {
                    return true;
                }
                i = end;
            } else {
                i++;
            }
        }
        return false;
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

    private static boolean cyrillic(String source, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.UnicodeScript.of(source.charAt(i)) == Character.UnicodeScript.CYRILLIC) {
                return true;
            }
        }
        return false;
    }
}
