package com.smartup24.cms.instance.audit.archive;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Records of the archive files ({@code audit_log_archives}) and of the partitions each one holds (V127). A verified
 * record is permanent: triggers let it change only by its file being removed, and never let it be deleted.
 */
@Repository
public class AuditArchiveRepository {

    private final JdbcClient jdbc;

    public AuditArchiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredArchive(long id, String fileKey) {}

    /** A partition to drop, by the name it was archived under, with the file that holds it. */
    public record ArchivedPartition(String recordedName, String fileKey) {}

    /** Takes the run lease unless another instance holds a live one. */
    public boolean acquireLease(String holder, Duration ttl) {
        return jdbc.sql("""
                        insert into audit_log_archive_lease (id, holder, expires_at)
                        values (1, :holder, now() + cast(:ttl as interval))
                        on conflict (id) do update set holder = excluded.holder, expires_at = excluded.expires_at
                        where audit_log_archive_lease.expires_at < now()
                        returning holder
                        """)
                .param("holder", holder)
                .param("ttl", ttl.toSeconds() + " seconds")
                .query(String.class)
                .optional()
                .isPresent();
    }

    public void releaseLease(String holder) {
        jdbc.sql("update audit_log_archive_lease set expires_at = now() where id = 1 and holder = :holder")
                .param("holder", holder)
                .update();
    }

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

    public void addPartition(long archiveId, String recordedName, long rows) {
        jdbc.sql("""
                        insert into audit_log_archive_partitions (archive_id, partition_name, row_count)
                        values (:archiveId, :name, :rows)
                        """)
                .param("archiveId", archiveId)
                .param("name", recordedName)
                .param("rows", rows)
                .update();
    }

    /** @return {@code false} when the record is gone (removed as unverified by a run that overlapped this one) */
    public boolean markVerified(long archiveId) {
        return jdbc.sql("update audit_log_archives set verified_at = now() where id = :id and verified_at is null")
                .param("id", archiveId)
                .update() == 1;
    }

    /** Partitions that any archive ever held: with deletion off, a partition is archived once. */
    public Set<String> everArchivedPartitionNames() {
        return new HashSet<>(jdbc.sql("select partition_name from audit_log_archive_partitions")
                .query(String.class)
                .list());
    }

    /**
     * Partitions held by an archive whose file still exists: with deletion on, a partition whose archive expired is
     * archived again before it may be dropped, so it never leaves the database without a copy.
     */
    public Set<String> liveArchivedPartitionNames() {
        return new HashSet<>(jdbc.sql("""
                        select ap.partition_name
                        from audit_log_archive_partitions ap
                        join audit_log_archives a on a.id = ap.archive_id
                        where a.file_deleted_at is null
                        """)
                .query(String.class)
                .list());
    }

    /** Archives left unverified by a run that failed half way; their records and files are removed before a retry. */
    public List<StoredArchive> unverified() {
        return jdbc.sql("select id, file_key from audit_log_archives where verified_at is null order by id")
                .query((rs, rowNum) -> new StoredArchive(rs.getLong("id"), rs.getString("file_key")))
                .list();
    }

    public void deleteUnverified(long archiveId) {
        jdbc.sql("delete from audit_log_archives where id = :id and verified_at is null").param("id", archiveId).update();
    }

    public Optional<Instant> lastVerifiedAt() {
        return jdbc.sql("select created_at from audit_log_archives where verified_at is not null "
                        + "order by created_at desc limit 1")
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    /** Partitions to drop: in a verified archive whose file still exists, not dropped yet. */
    public List<ArchivedPartition> partitionsToDrop() {
        return jdbc.sql("""
                        select ap.partition_name, a.file_key
                        from audit_log_archive_partitions ap
                        join audit_log_archives a on a.id = ap.archive_id
                        where a.verified_at is not null and a.file_deleted_at is null and ap.dropped_at is null
                        order by ap.partition_name
                        """)
                .query((rs, rowNum) -> new ArchivedPartition(rs.getString("partition_name"), rs.getString("file_key")))
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
        jdbc.sql("update audit_log_archives set file_deleted_at = now() where id = :id and file_deleted_at is null")
                .param("id", archiveId)
                .update();
    }
}
