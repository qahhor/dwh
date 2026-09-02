package com.greenwhite.dwh.instance.config.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BackupStatusReaderTest {

    @TempDir
    Path directory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void productionConstructorIsResolvableBySpring() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test",
                    Map.of("dwh.backup.status-file", directory.resolve("status.json").toString())));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(BackupStatusReader.class);

            context.refresh();

            assertThat(context.getBean(BackupStatusReader.class).read())
                    .isEqualTo(new BackupStatus("NEVER", null, null));
        }
    }

    @Test
    void readsTypedSuccessfulStatusAndDropsUntrustedFields() throws Exception {
        Instant completedAt = Instant.parse("2026-09-02T03:00:00Z");
        Path statusFile = directory.resolve("status.json");
        Files.writeString(statusFile, """
                {
                  "status": "SUCCESS",
                  "completedAt": "2026-09-02T03:00:00Z",
                  "archivePath": "/backups/customer.dump.age",
                  "repository": "s3://access-key:secret@example.invalid/private",
                  "error": "DB_PASSWORD=must-not-leak"
                }
                """);

        BackupStatus status = new BackupStatusReader(statusFile, objectMapper).read();

        assertThat(status).isEqualTo(new BackupStatus("SUCCESS", completedAt, null));
        assertThat(status.toString())
                .doesNotContain("customer.dump", "access-key", "secret", "DB_PASSWORD");
    }

    @Test
    void missingFileMeansBackupHasNeverRun() {
        BackupStatus status = new BackupStatusReader(directory.resolve("missing.json"), objectMapper).read();

        assertThat(status).isEqualTo(new BackupStatus("NEVER", null, null));
    }

    @Test
    void oversizedFileIsUnknownAndIsNotParsed() throws Exception {
        Path statusFile = directory.resolve("oversized.json");
        Files.writeString(statusFile, "x".repeat(16 * 1024 + 1));

        assertThat(new BackupStatusReader(statusFile, objectMapper).read())
                .isEqualTo(new BackupStatus("UNKNOWN", null, null));
    }

    @Test
    void malformedFileIsUnknown() throws Exception {
        Path statusFile = directory.resolve("malformed.json");
        Files.writeString(statusFile, "{not-json");

        assertThat(new BackupStatusReader(statusFile, objectMapper).read())
                .isEqualTo(new BackupStatus("UNKNOWN", null, null));
    }

    @Test
    void unsupportedStateAndFailureCodeAreNeutralized() throws Exception {
        Path statusFile = directory.resolve("untrusted.json");
        Files.writeString(statusFile, """
                {"status":"RUNNING","completedAt":"not-an-instant","failureCode":"password=secret"}
                """);

        assertThat(new BackupStatusReader(statusFile, objectMapper).read())
                .isEqualTo(new BackupStatus("UNKNOWN", null, null));
    }
}
