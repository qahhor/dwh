package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.4, acceptance "notes are at most two server files" (ADR-0032, 15, step 5): on the general runtime
 * an entity is its declaration and, when it needs them, its hooks — no controller, service or repository of its own.
 */
class EntityFileBudgetTest {

    private static final Path NOTES = Path.of("src/main/java/com/smartup24/cms/instance/ms/note");

    @Test
    @DisplayName("5.4: the notes module is at most two server files besides package-info")
    void notesAreAtMostTwoServerFiles() throws IOException {
        List<String> files;
        try (Stream<Path> tree = Files.walk(NOTES)) {
            files = tree.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals("package-info.java"))
                    .sorted()
                    .toList();
        }
        assertThat(files).as("the server files of ms.note").isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(files).as("no controller, service or repository").noneMatch(name -> name.matches(
                ".*(Controller|Service|Repository|Records)\\.java"));
    }
}
