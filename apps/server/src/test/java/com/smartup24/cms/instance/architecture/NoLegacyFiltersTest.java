package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.6, acceptance "{@code Legacy*Filters} = 0" (ADR-0032, 8): an entity on the model filters its list
 * with the DSL of ADR-0016 only — the flat query parameters a list took before the registry are gone with the
 * entity's own controller, and nothing keeps a class for them.
 */
class NoLegacyFiltersTest {

    private static final Path SOURCES = Path.of("src/main/java/com/smartup24/cms");

    /**
     * Flat filters of a list whose entity is still on the way to the model (plan 10/10, item 5.6: the tasks): each
     * leaves this set together with its class, and the set ends empty.
     */
    private static final Set<String> PENDING = Set.of("LegacyTaskFilters");

    @Test
    @DisplayName("5.6: no class of flat list filters besides the pending ones; the users' are gone")
    void noLegacyFilters() throws IOException {
        List<String> found;
        try (Stream<Path> tree = Files.walk(SOURCES)) {
            found = tree.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("Legacy\\w*Filters\\.java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .toList();
        }
        assertThat(found).as("Legacy*Filters classes").isSubsetOf(PENDING).doesNotContain("LegacyUserFilters");
        assertThat(sourcesMention("LegacyUserFilters"))
                .as("the user list's flat filters (ADR-0032, 8)")
                .isFalse();
    }

    private static boolean sourcesMention(String name) throws IOException {
        try (Stream<Path> tree = Files.walk(SOURCES)) {
            return tree.filter(path -> path.toString().endsWith(".java")).anyMatch(path -> {
                try {
                    return Files.readString(path).contains(name);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read " + path, e);
                }
            });
        }
    }
}
