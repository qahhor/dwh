package com.smartup24.cms.instance.audit;

import com.smartup24.cms.instance.audit.archive.AuditArchiveProperties;
import com.smartup24.cms.instance.audit.archive.AuditArchiveRepository;
import com.smartup24.cms.instance.audit.archive.AuditArchiveService;
import com.smartup24.cms.instance.audit.archive.AuditArchiveStore;
import com.smartup24.cms.instance.audit.archive.LocalAuditArchiveStore;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.support.TestDatabases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision of 2026-09-27: the audit log is archived weekly or at 100 MB, the archive is verified, archives are kept
 * 90 days, and a partition leaves the database only when deletion is on and a verified archive holds exactly its
 * rows. Runs on the real migrated schema (V127) with a local store in a temporary directory.
 */
class AuditArchiveIntegrationTest {

    private static final LocalDate APRIL_5 = LocalDate.of(2021, 4, 5);
    private static final String DAY_NAME = "audit_log_2021_04_05";

    @TempDir
    Path archiveDir;

    @Test
    @DisplayName("V127: empty future months gave way to daily partitions, 31 days of them exist; a monthly month covers its days")
    void dailyPartitionsReplaceEmptyFutureMonths() {
        var db = new Db("dwh_audit_v127");
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertThat(db.partitions.exists(current)).as("the current month stays monthly").isTrue();
        assertThat(db.partitions.exists(current.plusMonths(2))).as("empty future months are gone").isFalse();
        assertThat(db.partitions.covers(today.plusDays(31))).as("the runway exists before the server starts").isTrue();
        assertThat(db.partitions.createDay(current.atDay(15))).isEqualTo(AuditPartitionRepository.partitionName(current));
        LocalDate future = current.plusMonths(3).atDay(3);
        assertThat(db.partitions.createDay(future)).isEqualTo(AuditPartitionRepository.dayPartitionName(future));
    }

    @Test
    @DisplayName("A run archives settled partitions into one verified file that restores row for row; nothing is deleted")
    void archiveIsVerifiedAndRestorable() throws IOException {
        var db = new Db("dwh_audit_archive");
        db.partitions.create(YearMonth.of(2021, 3));
        db.insert(LocalDate.of(2021, 3, 10), "march-1", "march-2");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "april-1", "april-2", "april-3");

        var result = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.archivedKey()).startsWith("audit-log_").endsWith(".jsonl.gz");
        List<String> lines = gunzip(Files.newInputStream(archiveDir.resolve(result.archivedKey())));
        assertThat(lines).anyMatch(line -> line.contains("\"row_pk\":\"march-1\""))
                .anyMatch(line -> line.contains("\"row_pk\":\"april-3\""));
        assertThat(db.count("select count(*) from audit_log_archives where verified_at is not null")).isEqualTo(1);
        assertThat(db.archivedPartitionNames()).contains("audit_log_2021_03", DAY_NAME);
        assertThat(archiveDir.resolve(".staging")).isEmptyDirectory();

        // The file restores: every line is a row of audit_log.
        db.jdbc.sql("create table audit_restore_probe (like audit_log)").update();
        for (String line : lines) {
            db.jdbc.sql("insert into audit_restore_probe select * from jsonb_populate_record(null::audit_restore_probe, cast(:line as jsonb))")
                    .param("line", line).update();
        }
        assertThat(db.count("select count(*) from audit_restore_probe where row_pk like 'april-%' or row_pk like 'march-%'"))
                .isEqualTo(5);
        assertThat(db.count("select count(*) from audit_log where row_pk like 'april-%' or row_pk like 'march-%'"))
                .as("deletion is off: the rows stay in the database").isEqualTo(5);

        LocalDate next = APRIL_5.plusDays(1);
        db.partitions.createDay(next);
        db.insert(next, "waiting");
        var again = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();
        assertThat(again.archivedKey()).as("something waits, but a week has not passed and 100 MB are not reached").isNull();
    }

    @Test
    @DisplayName("With deletion on only archived partitions leave; the database refuses unarchived and open ones")
    void deletionTakesOnlyArchivedPartitions() {
        var db = new Db("dwh_audit_archive_delete");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "gone-1", "gone-2");

        var result = db.service(archiveDir, true, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.droppedPartitions()).contains(DAY_NAME);
        assertThat(db.partitions.covers(APRIL_5)).isFalse();
        assertThat(db.count("select count(*) from audit_log where row_pk like 'gone-%'")).isZero();
        assertThat(db.count("select count(*) from audit_log_archive_partitions "
                + "where partition_name = '" + DAY_NAME + "' and dropped_at is not null")).isEqualTo(1);

        LocalDate unarchived = APRIL_5.plusDays(1);
        db.partitions.createDay(unarchived);
        assertThatThrownBy(() -> db.dropByName(AuditPartitionRepository.dayPartitionName(unarchived)))
                .hasMessageContaining("no verified archive");
        assertThatThrownBy(() -> db.dropByName(AuditPartitionRepository.partitionName(YearMonth.now(ZoneOffset.UTC))))
                .hasMessageContaining("still open");
    }

    @Test
    @DisplayName("A row that reaches a partition after its archive keeps the partition in the database")
    void rowAddedAfterTheArchiveKeepsThePartition() {
        var db = new Db("dwh_audit_archive_late");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "early");
        db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();
        db.insert(APRIL_5, "late");

        var result = db.service(archiveDir, true, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.droppedPartitions()).doesNotContain(DAY_NAME);
        assertThat(db.count("select count(*) from audit_log where row_pk = 'late'")).isEqualTo(1);
        assertThatThrownBy(() -> db.dropByName(DAY_NAME)).hasMessageContaining("in no copy");
    }

    @Test
    @DisplayName("100 MB of waiting partitions start an archive before the week is over")
    void sizeStartsAnArchiveBeforeTheWeek() {
        var db = new Db("dwh_audit_archive_size");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "first");
        assertThat(db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run().archivedKey())
                .isNotNull();

        LocalDate next = APRIL_5.plusDays(1);
        db.partitions.createDay(next);
        db.insert(next, "second");
        assertThat(db.service(archiveDir, false, DataSize.ofGigabytes(1), db.localStore(archiveDir)).run().archivedKey())
                .as("under the threshold, within the week").isNull();
        assertThat(db.service(archiveDir, false, DataSize.ofBytes(1), db.localStore(archiveDir)).run().archivedKey())
                .as("over the threshold").isNotNull();
    }

    @Test
    @DisplayName("An expired file is removed; with deletion on the partition is archived again before it leaves")
    void expiredArchiveIsRenewedBeforeTheDrop() {
        var db = new Db("dwh_audit_archive_expiry");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "old");
        String first = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run().archivedKey();
        db.age(first, 91);

        var result = db.service(archiveDir, true, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.expiredKeys()).containsExactly(first);
        assertThat(archiveDir.resolve(first)).doesNotExist();
        assertThat(result.archivedKey()).as("a new copy first").isNotNull().isNotEqualTo(first);
        assertThat(archiveDir.resolve(result.archivedKey())).exists();
        assertThat(result.droppedPartitions()).contains(DAY_NAME);
    }

    @Test
    @DisplayName("With deletion off an expired file is not renewed: the archive was a copy, the database keeps the rows")
    void expiredArchiveIsNotRenewedWithoutDeletion() {
        var db = new Db("dwh_audit_archive_expiry_keep");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "kept");
        String first = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run().archivedKey();
        db.age(first, 91);

        var result = db.service(archiveDir, false, DataSize.ofBytes(1), db.localStore(archiveDir)).run();

        assertThat(result.expiredKeys()).containsExactly(first);
        assertThat(result.archivedKey()).isNull();
        assertThat(db.partitions.covers(APRIL_5)).isTrue();
    }

    @Test
    @DisplayName("A partition renamed by retention is not archived twice and can still leave")
    void retentionRenameKeepsTheArchive() {
        var db = new Db("dwh_audit_archive_renamed");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "renamed");
        db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();
        assertThat(db.partitions.detachDay(APRIL_5)).isEqualTo("audit_log_archived_2021_04_05");

        assertThat(db.service(archiveDir, false, DataSize.ofBytes(1), db.localStore(archiveDir)).run().archivedKey())
                .as("the renamed table is the same, archived partition").isNull();
        var result = db.service(archiveDir, true, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.droppedPartitions()).contains("audit_log_archived_2021_04_05");
        assertThat(db.count("select count(*) from pg_class where relname = 'audit_log_archived_2021_04_05'")).isZero();
    }

    @Test
    @DisplayName("A file missing from the store is no copy: the partition stays")
    void missingFileKeepsThePartition() throws IOException {
        var db = new Db("dwh_audit_archive_missing");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "orphan");
        String key = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run().archivedKey();
        Files.delete(archiveDir.resolve(key));

        var result = db.service(archiveDir, true, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.droppedPartitions()).isEmpty();
        assertThat(db.partitions.covers(APRIL_5)).isTrue();
    }

    @Test
    @DisplayName("An archive that does not read back — cut short or with other content — is not verified and is retried")
    void unverifiableArchiveIsRetried() throws IOException {
        var db = new Db("dwh_audit_archive_retry");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "retry");
        var good = db.localStore(archiveDir);
        byte[] otherContent = gzip("{\"row_pk\":\"one\"}\n{\"row_pk\":\"two\"}\n");

        for (byte[] served : List.of(new byte[]{31, -117, 8, 0}, otherContent)) {
            assertThatThrownBy(() -> db.service(archiveDir, true, DataSize.ofMegabytes(100), serving(good, served)).run());
            assertThat(db.count("select count(*) from audit_log_archives where verified_at is null")).isEqualTo(1);
            assertThat(db.partitions.covers(APRIL_5)).as("nothing is dropped without a verified archive").isTrue();
        }

        var result = db.service(archiveDir, false, DataSize.ofMegabytes(100), good).run();

        assertThat(result.archivedKey()).isNotNull();
        assertThat(db.count("select count(*) from audit_log_archives where verified_at is null")).isZero();
        assertThat(db.count("select count(*) from audit_log_archives")).isEqualTo(1);
    }

    @Test
    @DisplayName("Another instance holding the lease makes a run step aside")
    void leaseKeepsRunsApart() {
        var db = new Db("dwh_audit_archive_lease");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "leased");
        assertThat(db.archives.acquireLease("other-instance", Duration.ofHours(1))).isTrue();

        var result = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.skipped()).isTrue();
        assertThat(db.count("select count(*) from audit_log_archives")).isZero();
    }

    @Test
    @DisplayName("The trace of a verified archive cannot be deleted or rewritten, nor recorded as verified")
    void verifiedArchiveRecordIsPermanent() {
        var db = new Db("dwh_audit_archive_trace");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "trace");
        String key = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run().archivedKey();

        assertThatThrownBy(() -> db.jdbc.sql("delete from audit_log_archives where file_key = :key").param("key", key).update())
                .hasMessageContaining("permanent");
        assertThatThrownBy(() -> db.jdbc.sql("update audit_log_archives set sha256 = 'forged' where file_key = :key")
                .param("key", key).update()).hasMessageContaining("changes only");
        assertThatThrownBy(() -> db.jdbc.sql("delete from audit_log_archive_partitions").update())
                .hasMessageContaining("permanent");
        assertThatThrownBy(() -> db.jdbc.sql("""
                        insert into audit_log_archives (file_key, storage, period_from, period_to, row_count, byte_size,
                                                        sha256, verified_at)
                        values ('forged.gz', 'local', now() - interval '2 days', now() - interval '1 day', 0, 0, 'x', now())
                        """).update())
                .hasMessageContaining("recorded unverified");
    }

    private static AuditArchiveStore serving(AuditArchiveStore good, byte[] served) {
        return new AuditArchiveStore() {
            @Override public String storage() { return good.storage(); }
            @Override public void put(String key, Path file) throws IOException { good.put(key, file); }
            @Override public InputStream open(String key) { return new ByteArrayInputStream(served); }
            @Override public boolean exists(String key) throws IOException { return good.exists(key); }
            @Override public void delete(String key) throws IOException { good.delete(key); }
        };
    }

    private static byte[] gzip(String text) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static List<String> gunzip(InputStream in) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    /** One migrated database per test: archives and partitions of one test must not meet another's. */
    private static final class Db {
        final JdbcClient jdbc;
        final DataSource ds;
        final AuditPartitionRepository partitions;
        final AuditArchiveRepository archives;
        final AuditLogService audit;

        Db(String name) {
            ds = TestDatabases.migratedCopy(name + "_" + UUID.randomUUID().toString().substring(0, 8));
            jdbc = JdbcClient.create(ds);
            partitions = new AuditPartitionRepository(jdbc);
            archives = new AuditArchiveRepository(jdbc);
            audit = new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null, new AuditDataRedactor());
        }

        LocalAuditArchiveStore localStore(Path dir) {
            return new LocalAuditArchiveStore(dir);
        }

        AuditArchiveService service(Path dir, boolean delete, DataSize threshold, AuditArchiveStore store) {
            var properties = new AuditArchiveProperties(true, "local", dir, Duration.ofDays(7), threshold,
                    Duration.ofDays(90), delete, new AuditArchiveProperties.S3(null, "auto", null, null, null, "audit/", true));
            return new AuditArchiveService(partitions, archives, store, properties, audit, jdbc,
                    new DataSourceTransactionManager(ds));
        }

        void insert(LocalDate day, String... rowPks) {
            for (String rowPk : rowPks) {
                jdbc.sql("""
                                insert into audit_log (table_name, row_pk, event, changed_at, new_row)
                                values ('archive_probe', :rowPk, 'I', cast(:at as timestamptz), '{"probe": true}'::jsonb)
                                """)
                        .param("rowPk", rowPk)
                        .param("at", day + " 10:00:00+00")
                        .update();
            }
        }

        /** Moves an archive back in time; only created_at, which the record guard lets no one else change. */
        void age(String key, int days) {
            jdbc.sql("alter table audit_log_archives disable trigger audit_log_archives_guard").update();
            jdbc.sql("update audit_log_archives set created_at = now() - make_interval(days => :days) where file_key = :key")
                    .param("days", days).param("key", key).update();
            jdbc.sql("alter table audit_log_archives enable trigger audit_log_archives_guard").update();
        }

        void dropByName(String name) {
            jdbc.sql("select audit_log_drop_archived_partition(:name)").param("name", name).query().singleValue();
        }

        List<String> archivedPartitionNames() {
            return jdbc.sql("select partition_name from audit_log_archive_partitions").query(String.class).list();
        }

        long count(String sql) {
            return jdbc.sql(sql).query(Long.class).single();
        }
    }
}
