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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision of 2026-09-27: the audit log is archived weekly or at 100 MB, the archive is verified, archives are kept
 * 90 days, and a partition leaves the database only when deletion is on and a verified archive holds it.
 * Runs on the real migrated schema (V127) with a local store in a temporary directory.
 */
class AuditArchiveIntegrationTest {

    private static final LocalDate APRIL_5 = LocalDate.of(2021, 4, 5);

    @TempDir
    Path archiveDir;

    @Test
    @DisplayName("V127: empty future months gave way to daily partitions; a month still monthly covers its days")
    void dailyPartitionsReplaceEmptyFutureMonths() {
        var db = new Db("dwh_audit_v127");
        YearMonth current = YearMonth.now(ZoneOffset.UTC);

        assertThat(db.partitions.exists(current)).as("the current month stays monthly").isTrue();
        assertThat(db.partitions.exists(current.plusMonths(2))).as("empty future months are gone").isFalse();
        assertThat(db.partitions.createDay(current.atDay(15))).isEqualTo(AuditPartitionRepository.partitionName(current));
        LocalDate future = current.plusMonths(2).atDay(3);
        assertThat(db.partitions.createDay(future)).isEqualTo(AuditPartitionRepository.dayPartitionName(future));
        assertThat(db.partitions.covers(future)).isTrue();
    }

    @Test
    @DisplayName("A run archives closed partitions into one verified file that restores row for row; nothing is deleted")
    void archiveIsVerifiedAndRestorable() throws IOException {
        var db = new Db("dwh_audit_archive");
        db.partitions.create(YearMonth.of(2021, 3));
        db.insert(LocalDate.of(2021, 3, 10), "march-1", "march-2");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "april-1", "april-2", "april-3");

        var result = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.archivedKey()).startsWith("audit-log_").endsWith(".jsonl.gz");
        Path file = archiveDir.resolve(result.archivedKey());
        List<String> lines = gunzip(Files.newInputStream(file));
        assertThat(lines).anyMatch(line -> line.contains("\"row_pk\":\"march-1\""))
                .anyMatch(line -> line.contains("\"row_pk\":\"april-3\""));
        assertThat(db.jdbc.sql("select verified_at is not null from audit_log_archives where file_key = :key")
                .param("key", result.archivedKey()).query(Boolean.class).single()).isTrue();
        assertThat(db.archivedPartitionNames()).contains("audit_log_2021_03", "audit_log_2021_04_05");
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

        var again = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();
        assertThat(again.archivedKey()).as("a week has not passed and 100 MB are not reached").isNull();
    }

    @Test
    @DisplayName("With deletion on only archived partitions leave; the database refuses unarchived and open ones")
    void deletionTakesOnlyArchivedPartitions() {
        var db = new Db("dwh_audit_archive_delete");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "gone-1", "gone-2");

        var result = db.service(archiveDir, true, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.droppedPartitions()).contains("audit_log_2021_04_05");
        assertThat(db.partitions.covers(APRIL_5)).isFalse();
        assertThat(db.count("select count(*) from audit_log where row_pk like 'gone-%'")).isZero();
        assertThat(db.count("select count(*) from audit_log_archive_partitions "
                + "where partition_name = 'audit_log_2021_04_05' and dropped_at is not null")).isEqualTo(1);

        LocalDate unarchived = APRIL_5.plusDays(1);
        db.partitions.createDay(unarchived);
        assertThatThrownBy(() -> db.dropByName(AuditPartitionRepository.dayPartitionName(unarchived)))
                .hasMessageContaining("no verified archive");
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        assertThatThrownBy(() -> db.dropByName(AuditPartitionRepository.partitionName(current)))
                .hasMessageContaining("still open");
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
    @DisplayName("Files older than the retention are removed, and their partitions can no longer be dropped")
    void expiredFilesAreRemovedAndNoLongerCountAsCopies() {
        var db = new Db("dwh_audit_archive_expiry");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "old");
        String key = db.service(archiveDir, false, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run().archivedKey();
        db.jdbc.sql("update audit_log_archives set created_at = now() - interval '91 days' where file_key = :key")
                .param("key", key).update();

        var result = db.service(archiveDir, true, DataSize.ofMegabytes(100), db.localStore(archiveDir)).run();

        assertThat(result.expiredKeys()).containsExactly(key);
        assertThat(archiveDir.resolve(key)).doesNotExist();
        assertThat(result.droppedPartitions())
                .as("deletion was switched on after the file expired: no copy, so the partition stays")
                .doesNotContain("audit_log_2021_04_05");
        assertThat(db.partitions.covers(APRIL_5)).isTrue();
    }

    @Test
    @DisplayName("An archive that does not read back is not verified; the next run removes it and tries again")
    void unreadableArchiveIsRetried() throws IOException {
        var db = new Db("dwh_audit_archive_retry");
        db.partitions.createDay(APRIL_5);
        db.insert(APRIL_5, "retry");
        var good = db.localStore(archiveDir);
        AuditArchiveStore corrupting = new AuditArchiveStore() {
            @Override public String storage() { return good.storage(); }
            @Override public void put(String key, Path file) throws IOException { good.put(key, file); }
            @Override public InputStream open(String key) { return new ByteArrayInputStream(new byte[]{31, -117, 8, 0}); }
            @Override public void delete(String key) throws IOException { good.delete(key); }
        };

        assertThatThrownBy(() -> db.service(archiveDir, true, DataSize.ofMegabytes(100), corrupting).run());
        assertThat(db.count("select count(*) from audit_log_archives where verified_at is null")).isEqualTo(1);
        assertThat(db.partitions.covers(APRIL_5)).as("nothing is dropped without a verified archive").isTrue();

        var result = db.service(archiveDir, false, DataSize.ofMegabytes(100), good).run();

        assertThat(result.archivedKey()).isNotNull();
        assertThat(db.count("select count(*) from audit_log_archives where verified_at is null")).isZero();
        assertThat(db.count("select count(*) from audit_log_archives")).isEqualTo(1);
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
