package com.smartup24.cms.instance.audit.archive;

import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository;
import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository.AuditPartition;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Archiving of the audit log (decision of 2026-09-27).
 *
 * <p>A run exports the closed partitions that no archive holds yet into one gzip file of JSON lines (one row of
 * {@code audit_log} per line, as {@code row_to_json} gives it), when a week has passed since the last archive or the
 * waiting partitions reach 100 MB. The file is recorded, stored (local directory or S3), read back and matched by
 * SHA-256 and row count; only then is it verified. With {@code delete-after-archive} on, the partitions of verified
 * archives leave the database; the database function refuses any other. Archive files older than the retention are
 * removed. Every step that removes something is a security event.
 */
@Service
public class AuditArchiveService {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String ACTOR = "audit-archive";

    private final AuditPartitionRepository partitions;
    private final AuditArchiveRepository archives;
    private final AuditArchiveStore store;
    private final AuditArchiveProperties properties;
    private final AuditLogService auditLogService;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    @Autowired
    public AuditArchiveService(AuditPartitionRepository partitions, AuditArchiveRepository archives,
                               AuditArchiveStore store, AuditArchiveProperties properties,
                               AuditLogService auditLogService, JdbcClient jdbc,
                               PlatformTransactionManager transactionManager) {
        this(partitions, archives, store, properties, auditLogService, jdbc, transactionManager, Clock.systemUTC());
    }

    AuditArchiveService(AuditPartitionRepository partitions, AuditArchiveRepository archives,
                        AuditArchiveStore store, AuditArchiveProperties properties,
                        AuditLogService auditLogService, JdbcClient jdbc,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.partitions = partitions;
        this.archives = archives;
        this.store = store;
        this.properties = properties;
        this.auditLogService = auditLogService;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /** What one run did. */
    public record RunResult(String archivedKey, List<String> droppedPartitions, List<String> expiredKeys) {}

    public RunResult run() {
        if (!properties.enabled()) {
            return new RunResult(null, List.of(), List.of());
        }
        removeUnverified();
        Instant now = clock.instant();
        String archived = archiveIfDue(now);
        // Expired files go first: a partition whose archive expires in this run has no copy left to be dropped with.
        List<String> expired = removeExpiredFiles(now);
        List<String> dropped = properties.deleteAfterArchive() ? dropArchivedPartitions() : List.of();
        return new RunResult(archived, dropped, expired);
    }

    /** Closed partitions that no archive holds, oldest first. */
    List<AuditPartition> waitingPartitions(LocalDate today) {
        Set<String> archivedNames = archives.archivedPartitionNames();
        return partitions.partitions().stream()
                .filter(partition -> partition.closedBy(today) && !archivedNames.contains(partition.name()))
                .toList();
    }

    private String archiveIfDue(Instant now) {
        List<AuditPartition> waiting = waitingPartitions(LocalDate.ofInstant(now, ZoneOffset.UTC));
        if (waiting.isEmpty()) {
            return null;
        }
        long waitingBytes = waiting.stream().mapToLong(partitions::sizeBytes).sum();
        boolean weekPassed = archives.lastVerifiedAt()
                .map(last -> !last.plus(properties.interval()).isAfter(now))
                .orElse(true);
        if (!weekPassed && waitingBytes < properties.sizeThreshold().toBytes()) {
            return null;
        }
        try {
            return archive(waiting, now);
        } catch (IOException e) {
            throw new UncheckedIOException("Audit archive failed", e);
        }
    }

    private String archive(List<AuditPartition> waiting, Instant now) throws IOException {
        LocalDate from = waiting.getFirst().from();
        LocalDate to = waiting.stream().map(AuditPartition::to).max(LocalDate::compareTo).orElseThrow();
        String key = "audit-log_" + from + "_" + to + "_" + STAMP.format(now) + ".jsonl.gz";

        Path staging = properties.localPath().resolve(".staging");
        Files.createDirectories(staging);
        Path file = Files.createTempFile(staging, "audit-", ".jsonl.gz");
        try {
            MessageDigest digest = sha256();
            Map<String, Long> rowsByPartition = new LinkedHashMap<>();
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(file), digest);
                 Writer writer = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(out), StandardCharsets.UTF_8))) {
                for (AuditPartition partition : waiting) {
                    rowsByPartition.put(partition.name(), export(partition, writer));
                }
            }
            long rows = rowsByPartition.values().stream().mapToLong(Long::longValue).sum();
            String sha256 = HexFormat.of().formatHex(digest.digest());
            long bytes = Files.size(file);

            long archiveId = archives.create(key, store.storage(), from.atStartOfDay().toInstant(ZoneOffset.UTC),
                    to.atStartOfDay().toInstant(ZoneOffset.UTC), rows, bytes, sha256);
            rowsByPartition.forEach((name, count) -> archives.addPartition(archiveId, name, count));
            store.put(key, file);
            verify(key, sha256, rows);
            archives.markVerified(archiveId);

            auditLogService.logSecurityEvent("AUDIT_ARCHIVED", null, null, ACTOR, Map.of(
                    "file", key, "storage", store.storage(), "rows", rows, "bytes", bytes,
                    "partitions", String.join(",", rowsByPartition.keySet())));
            log.info("audit_archived file={} storage={} partitions={} rows={} bytes={}",
                    key, store.storage(), rowsByPartition.size(), rows, bytes);
            return key;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** One partition, row by row through a cursor (the pool's fetch size), in key order. */
    private long export(AuditPartition partition, Writer writer) {
        // The name comes from pg_class and matched the partition pattern: it is quoted all the same.
        String table = "\"" + partition.name().replace("\"", "\"\"") + "\"";
        Long count = readOnly.execute(status -> {
            long[] lines = {0};
            jdbc.sql("select row_to_json(t)::text from " + table + " t order by changed_at, id")
                    .query(rs -> {
                        try {
                            writer.write(rs.getString(1));
                            writer.write('\n');
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        lines[0]++;
                    });
            return lines[0];
        });
        return count != null ? count : 0L;
    }

    /** Reads the stored file back: the same SHA-256 and the same number of lines, or the archive does not count. */
    private void verify(String key, String expectedSha256, long expectedRows) throws IOException {
        MessageDigest digest = sha256();
        long lines = 0;
        try (InputStream stored = store.open(key);
             DigestInputStream digested = new DigestInputStream(stored, digest)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(digested), StandardCharsets.UTF_8));
            while (reader.readLine() != null) {
                lines++;
            }
            digested.transferTo(OutputStream.nullOutputStream());
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expectedSha256) || lines != expectedRows) {
            throw new IOException("Stored archive " + key + " does not match: sha256 " + actual + ", " + lines
                    + " lines instead of " + expectedRows);
        }
    }

    /** A run that failed half way leaves an unverified record, maybe a file: both go, the partitions wait again. */
    private void removeUnverified() {
        for (var archive : archives.unverified()) {
            try {
                store.delete(archive.fileKey());
            } catch (IOException | RuntimeException e) {
                log.warn("audit_archive_cleanup_failed file={}", archive.fileKey(), e);
            }
            archives.delete(archive.id());
        }
    }

    private List<String> dropArchivedPartitions() {
        Map<String, AuditPartition> existing = new LinkedHashMap<>();
        partitions.partitions().forEach(partition -> existing.put(partition.name(), partition));
        List<String> dropped = new ArrayList<>();
        for (String name : archives.partitionsToDrop()) {
            AuditPartition partition = existing.get(name);
            if (partition == null) {
                continue;
            }
            try {
                partitions.dropArchived(partition);
                dropped.add(name);
            } catch (RuntimeException e) {
                log.error("audit_partition_drop_failed partition={}: {}", name, e.getMessage());
            }
        }
        if (!dropped.isEmpty()) {
            auditLogService.logSecurityEvent("AUDIT_PARTITIONS_DROPPED", null, null, ACTOR,
                    Map.of("partitions", String.join(",", dropped)));
            log.warn("audit_partitions_dropped after verified archive: {}", String.join(", ", dropped));
        }
        return dropped;
    }

    private List<String> removeExpiredFiles(Instant now) {
        List<String> removed = new ArrayList<>();
        for (var archive : archives.expiredBefore(now.minus(properties.retention()))) {
            try {
                store.delete(archive.fileKey());
                archives.markFileDeleted(archive.id());
                removed.add(archive.fileKey());
            } catch (IOException | RuntimeException e) {
                log.error("audit_archive_expiry_failed file={}", archive.fileKey(), e);
            }
        }
        if (!removed.isEmpty()) {
            auditLogService.logSecurityEvent("AUDIT_ARCHIVES_EXPIRED", null, null, ACTOR,
                    Map.of("files", String.join(",", removed), "retention", properties.retention().toString()));
        }
        return removed;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
