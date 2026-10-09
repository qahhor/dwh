package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 3.14: the server writes no Russian text of its own. What a user reads comes from a catalog key in
 * the user's language (ru, uz, en; {@code MdUserTexts}, {@code ApiException} keys); logs and internal exception
 * messages are English. A string or char literal with Cyrillic letters stays only in the files below, where it is data,
 * each with its reason.
 */
class CyrillicLiteralsTest {

    private static final Path SOURCES = Path.of("src/main/java/com/smartup24/cms/instance");

    /** Files whose Cyrillic literals are data, not text the server writes; the path is under {@link #SOURCES}. */
    static final Map<String, String> ALLOWED = Map.of(
            "md/pref/MdFormCatalog.java",
            "names of the permission catalog in its source language: md_forms stores Russian and the language"
                    + " editor translates them (ADR-0031)",
            "search/service/QueryLanguageConverter.java",
            "the Russian keyboard layout, letters and words the search converts a query with",
            "config/demo/DemoDataset.java",
            "the fictional Russian records of the demo stand (plan 10/10, item 6.5)",
            "common/entity/importing/EntityImportCells.java",
            "the Russian words an import accepts for yes and no in a cell");

    @Test
    @DisplayName("3.14: no Cyrillic string or char literal in main code outside the allowed data files")
    void noCyrillicLiteralsOutsideTheAllowList() {
        List<String> found = javaFiles()
                .filter(file -> !ALLOWED.containsKey(relative(file)))
                .flatMap(file ->
                        CommentLines.javaLiteralLines(read(file)).stream().map(line -> relative(file) + ":" + line))
                .sorted()
                .toList();
        assertThat(found)
                .as("put the text in the ru, uz and en catalogs and render it in the reader's language,"
                        + " write a log or an internal message in English, or list a data file with its reason")
                .isEmpty();
    }

    @Test
    @DisplayName("3.14: every allowed file still holds Cyrillic data")
    void theAllowListIsNotStale() {
        for (String file : ALLOWED.keySet()) {
            assertThat(CommentLines.javaLiteralLines(read(SOURCES.resolve(file))))
                    .as(file + " no longer holds Cyrillic literals: drop it from the list")
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("3.14: the scan sees literals and text blocks, not comments")
    void theScanSeesLiteralsOnly() {
        String source = """
                // комментарий
                /* блок
                   комментария */
                String a = "текст";
                char b = 'ж';
                String c = \"""
                    блок текста
                    \""";
                String d = "plain"; // по-русски
                """;
        assertThat(CommentLines.javaLiteralLines(source)).containsExactly(4, 5, 7);
    }

    private static Stream<Path> javaFiles() {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path file) {
        return SOURCES.relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
