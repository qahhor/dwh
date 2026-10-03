package com.acme.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The example proves the contract only while it uses nothing but the platform's API (ADR-0033, 8): no source of the
 * module imports the platform's implementation ({@code com.smartup24.cms.instance}) or anything of the platform outside
 * {@code com.smartup24.cms.platform.api}.
 */
class LibraryModuleBoundaryTest {

    private static final Pattern IMPORT =
            Pattern.compile("^import (?:static )?(com\\.smartup24\\.[\\w.]+);", Pattern.MULTILINE);

    @Test
    void theModuleImportsThePlatformApiOnly() throws IOException {
        List<String> foreign;
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            foreign = sources.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(LibraryModuleBoundaryTest::platformImports)
                    .filter(name -> !name.startsWith("com.smartup24.cms.platform.api."))
                    .toList();
        }
        assertThat(foreign).isEmpty();
    }

    @Test
    void theManifestNamesThisConfiguration() throws IOException {
        String manifest = Files.readString(
                Path.of("src", "main", "resources", "META-INF", "smartupcms", "modules", "library.json"));
        assertThat(manifest)
                .contains("\"configuration\": \"" + LibraryModule.class.getName() + "\"")
                .contains("\"code\": \"" + LibraryModule.MODULE + "\"");
    }

    private static Stream<String> platformImports(Path source) {
        try {
            Matcher matcher = IMPORT.matcher(Files.readString(source));
            Stream.Builder<String> names = Stream.builder();
            while (matcher.find()) names.add(matcher.group(1));
            return names.build();
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable source " + source, e);
        }
    }
}
