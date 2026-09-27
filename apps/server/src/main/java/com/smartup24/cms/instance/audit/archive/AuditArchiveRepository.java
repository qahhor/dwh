package com.smartup24.cms.instance.audit.archive;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Records of the archive files ({@code audit_log_archives}) and of the partitions each one holds (V127). */
@Repository
public class AuditArchiveRepository {

    private final JdbcClient jdbc;

    public AuditArchiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredArchive(long id, String fileKey) {}

    public long create(String fileKey, String storage, Instant from, Instant to, long rows, long bytes, String sha256) {
        return jdbc.sql("""
                        insert into audit_log_archives (file_key, storage, period_from, period_to, row_count, byte_size, sha256)
                        values (:fileKey, :storage, :from, :to, :rows, :bytes, :sha256)
                        returning id
                        """)
                .param("fileKey", fileKey)
                .param("storage", storage)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("rows", rows)
                .param("bytes", bytes)
                .param("sha256", sha256)
                .query(Long.class)
                .single();
    }

    public void addPartition(long archiveId, String partitionName, long rows) {
        jdbc.sql("""
                        insert into audit_log_archive_partitions (archive_id, partition_name, row_count)
                        values (:archiveId, :name, :rows)
                        """)
                .param("archiveId", archiveId)
                .param("name", partitionName)
                .param("rows", rows)
                .update();
    }

    public void markVerified(long archiveId) {
        jdbc.sql("update audit_log_archives set verified_at = now() where id = :id")
                .param("id", archiveId)
                .update();
    }

    /** Partitions that an archive holds already: they are not exported again. */
    public Set<String> archivedPartitionNames() {
        return new HashSet<>(jdbc.sql("select partition_name from audit_log_archive_partitions")
                .query(String.class)
                .list());
    }

    /** Archives left unverified by a run that failed half way; their records and files are removed before a retry. */
    public List<StoredArchive> unverified() {
        return jdbc.sql("select id, file_key from audit_log_archives where verified_at is null order by id")
                .query((rs, rowNum) -> new StoredArchive(rs.getLong("id"), rs.getString("file_key")))
                .list();
    }

    public void delete(long archiveId) {
        jdbc.sql("delete from audit_log_archives where id = :id").param("id", archiveId).update();
    }

    public Optional<Instant> lastVerifiedAt() {
        return jdbc.sql("select created_at from audit_log_archives where verified_at is not null "
                        + "order by created_at desc limit 1")
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    /** Partitions to drop from the database: in a verified archive whose file still exists, not dropped yet. */
    public List<String> partitionsToDrop() {
        return jdbc.sql("""
                        select ap.partition_name
                        from audit_log_archive_partitions ap
                        join audit_log_archives a on a.id = ap.archive_id
                        where a.verified_at is not null and a.file_deleted_at is null and ap.dropped_at is null
                        order by ap.partition_name
                        """)
                .query(String.class)
                .list();
    }

    /** Verified archive files older than the retention, not removed yet. */
    public List<StoredArchive> expiredBefore(Instant cutoff) {
        return jdbc.sql("""
                        select id, file_key from audit_log_archives
                        where verified_at is not null and file_deleted_at is null and created_at < :cutoff
                        order by id
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .query((rs, rowNum) -> new StoredArchive(rs.getLong("id"), rs.getString("file_key")))
                .list();
    }

    public void markFileDeleted(long archiveId) {
        jdbc.sql("update audit_log_archives set file_deleted_at = now() where id = :id")
                .param("id", archiveId)
                .update();
    }
}
