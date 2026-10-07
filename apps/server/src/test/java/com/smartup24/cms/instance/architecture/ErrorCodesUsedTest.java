package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every response code of {@link ErrorCode} is one the server can answer with (ADR-0021): its constant is named as
 * {@code ErrorCode.<NAME>} in the main code of the server or a library. A code nobody throws only makes the catalogs
 * and the clients' handling promise an answer that never comes. A code kept on purpose goes to {@link #KEPT} with its
 * reason.
 */
class ErrorCodesUsedTest {

    private static final Path LIBS = Path.of("../../libs");
    private static final Path SERVER_MAIN = Path.of("src/main/java");
    private static final String ERROR_CODE_FILE = "ErrorCode.java";

    /** Codes kept without a use in the main code, each with its reason; empty today. */
    private static final Map<ErrorCode, String> KEPT = Map.of();

    @Test
    @DisplayName("ADR-0021: every ErrorCode constant is used by the main code or kept with a reason")
    void everyCodeIsUsed() throws IOException {
        String sources = mainSources();
        List<String> unused = new ArrayList<>();
        List<String> keptButUsed = new ArrayList<>();
        for (ErrorCode code : ErrorCode.values()) {
            boolean used = Pattern.compile("\\bErrorCode\\." + code.name() + "\\b")
                    .matcher(sources)
                    .find();
            if (!used && !KEPT.containsKey(code)) {
                unused.add(code.name());
            }
            if (used && KEPT.containsKey(code)) {
                keptButUsed.add(code.name());
            }
        }
        assertThat(unused)
                .as(
                        "ErrorCode constants nobody throws: remove them with their catalog texts, or keep one with a reason")
                .isEmpty();
        assertThat(keptButUsed)
                .as("kept codes that are used after all leave KEPT")
                .isEmpty();
    }

    @Test
    @DisplayName("ADR-0021: the scan reads the server and the libraries")
    void scanReadsServerAndLibraries() throws IOException {
        assertThat(mainSources()).contains("ErrorCode.REVISION_CONFLICT").doesNotContain("enum ErrorCode");
    }

    private static String mainSources() throws IOException {
        List<Path> roots = new ArrayList<>(List.of(SERVER_MAIN));
        try (Stream<Path> libs = Files.list(LIBS)) {
            libs.map(lib -> lib.resolve("src/main/java"))
                    .filter(Files::isDirectory)
                    .forEach(roots::add);
        }
        StringBuilder all = new StringBuilder();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals(ERROR_CODE_FILE))
                        .toList()) {
                    all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return all.toString();
    }
}
