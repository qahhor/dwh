package com.greenwhite.dwh.instance.config.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/** Reads the bounded, non-secret status contract written by the backup sidecar. */
@Component
public class BackupStatusReader {

    static final int MAX_STATUS_BYTES = 16 * 1024;

    private static final Set<String> STATES = Set.of("SUCCESS", "FAILED", "NEVER", "UNKNOWN");
    private static final Set<String> FAILURE_CODES = Set.of(
            "CONFIGURATION_MISSING",
            "DATABASE_DUMP_FAILED",
            "ENCRYPTION_FAILED",
            "UPLOAD_FAILED",
            "UNKNOWN");
    private static final BackupStatus NEVER = new BackupStatus("NEVER", null, null);
    private static final BackupStatus UNKNOWN = new BackupStatus("UNKNOWN", null, null);

    private final Path statusFile;
    private final ObjectMapper objectMapper;

    @Autowired
    public BackupStatusReader(
            @Value("${dwh.backup.status-file:/var/lib/smartupcms/backup/status.json}") String statusFile,
            ObjectMapper objectMapper) {
        this(Path.of(statusFile), objectMapper);
    }

    BackupStatusReader(Path statusFile, ObjectMapper objectMapper) {
        this.statusFile = statusFile.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public BackupStatus read() {
        try {
            if (!Files.exists(statusFile, LinkOption.NOFOLLOW_LINKS)) {
                return NEVER;
            }

            BasicFileAttributes attributes = Files.readAttributes(
                    statusFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_STATUS_BYTES) {
                return UNKNOWN;
            }

            byte[] contents = readBounded();
            if (contents.length > MAX_STATUS_BYTES) {
                return UNKNOWN;
            }

            BackupStatusDocument document = objectMapper.readerFor(BackupStatusDocument.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(contents);
            return sanitize(document);
        } catch (Exception ignored) {
            return UNKNOWN;
        }
    }

    private byte[] readBounded() throws Exception {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(statusFile, options);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(Math.min(4096, MAX_STATUS_BYTES + 1));
            while (output.size() <= MAX_STATUS_BYTES) {
                buffer.clear();
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    break;
                }
                output.write(buffer.array(), 0, read);
            }
            return output.toByteArray();
        }
    }

    private static BackupStatus sanitize(BackupStatusDocument document) {
        if (document == null || document.status() == null) {
            return UNKNOWN;
        }
        String state = document.status().trim().toUpperCase(Locale.ROOT);
        if (!STATES.contains(state) || "UNKNOWN".equals(state)) {
            return UNKNOWN;
        }
        if ("NEVER".equals(state)) {
            return NEVER;
        }

        Instant completedAt;
        try {
            completedAt = Instant.parse(document.completedAt());
        } catch (Exception ignored) {
            return UNKNOWN;
        }

        String failureCode = null;
        if ("FAILED".equals(state) && document.failureCode() != null) {
            String candidate = document.failureCode().trim().toUpperCase(Locale.ROOT);
            failureCode = FAILURE_CODES.contains(candidate) ? candidate : "UNKNOWN";
        }
        return new BackupStatus(state, completedAt, failureCode);
    }

    private record BackupStatusDocument(String status, String completedAt, String failureCode) {
    }
}
