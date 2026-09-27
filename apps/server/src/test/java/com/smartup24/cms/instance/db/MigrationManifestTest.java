package com.smartup24.cms.instance.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 10/10, item 0.5: a migration, once in the manifest, never changes and never disappears.
 *
 * <p>Flyway keeps a checksum of every applied file. An edited file (V100 got a new comment) or a deleted one (V101)
 * stops every database that already applied it: {@code flyway validate} refuses to start. The manifest holds the
 * SHA-256 of every file (line endings normalised, as Flyway does), and this test fails on any edit or removal.
 *
 * <p>A new migration is added to the manifest with
 * {@code mvn test -pl apps/server -Dtest=MigrationManifestTest -Dmigrations.manifest.append=true}: the mode appends
 * missing files and never rewrites a listed one. To correct a migration that is not merged yet, delete its line by
 * hand and append again; a merged migration is corrected by a new one. Stands that applied a changed or deleted file
 * are repaired as described in {@code docs/ops/migration-repair.md}.
 */
class MigrationManifestTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final List<String> LOCATIONS = List.of("db/migration", "db/dwh");
    private static final Path MANIFEST = Path.of("src/test/resources/migration-manifest.sha256");

    @Test
    @DisplayName("0.5: every migration is in the manifest, and no listed migration is changed or gone")
    void migrationsMatchTheManifest() throws IOException {
        Map<String, String> onDisk = hashesOnDisk();
        if (Boolean.getBoolean("migrations.manifest.append")) {
            append(onDisk, readManifest());
        }
        Map<String, String> listed = readManifest();

        List<String> problems = new ArrayList<>();
        listed.forEach((file, hash) -> {
            String actual = onDisk.get(file);
            if (actual == null) {
                problems.add(file + ": removed after release; add a new migration instead");
            } else if (!actual.equals(hash)) {
                problems.add(file + ": changed after release (sha256 " + actual + "); add a new migration instead");
            }
        });
        onDisk.keySet().stream()
                .filter(file -> !listed.containsKey(file))
                .forEach(file -> problems.add(file + ": not in the manifest; run the test with"
                        + " -Dmigrations.manifest.append=true"));

        assertThat(problems).as("migrations against %s", MANIFEST).isEmpty();
    }

    private static Map<String, String> hashesOnDisk() throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        for (String location : LOCATIONS) {
            try (Stream<Path> files = Files.list(RESOURCES.resolve(location))) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                    hashes.put(location + "/" + file.getFileName(), sha256(file));
                }
            }
        }
        return hashes;
    }

    /** Line endings do not count: a checkout on Windows must not look like an edit. */
    static String sha256(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> readManifest() throws IOException {
        Map<String, String> listed = new LinkedHashMap<>();
        if (!Files.exists(MANIFEST)) {
            return listed;
        }
        for (String line : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+", 2);
            listed.put(parts[1], parts[0]);
        }
        return listed;
    }

    private static void append(Map<String, String> onDisk, Map<String, String> listed) throws IOException {
        StringBuilder added = new StringBuilder();
        onDisk.forEach((file, hash) -> {
            if (!listed.containsKey(file)) {
                added.append(hash).append("  ").append(file).append('\n');
            }
        });
        if (!added.isEmpty()) {
            Files.writeString(MANIFEST, added.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
