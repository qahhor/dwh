package com.smartup24.cms.instance.ms.task.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Files attached to a task ({@code ms_task_files}); the file itself belongs to the files module. */
@Repository
public class MsTaskFileRepository {

    private final JdbcClient jdbcClient;

    public MsTaskFileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void attachFile(Long taskId, UUID fileId) {
        jdbcClient.sql("""
                insert into ms_task_files (task_id, file_id, created_at)
                values (:taskId, :fileId, now())
                on conflict (task_id, file_id) do nothing
                """).param("taskId", taskId).param("fileId", fileId).update();
    }

    public void detachFile(Long taskId, UUID fileId) {
        jdbcClient.sql("""
                delete from ms_task_files
                where task_id = :taskId and file_id = :fileId
                """).param("taskId", taskId).param("fileId", fileId).update();
    }

    public List<TaskFileRecord> listTaskFiles(Long taskId) {
        return jdbcClient
                .sql("""
                select tf.file_id, f.original_name, f.size_bytes, f.mime_type, tf.created_at
                from ms_task_files tf
                join mf_pub_files f on f.id = tf.file_id
                where tf.task_id = :taskId
                order by tf.created_at asc
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new TaskFileRecord(
                        UUID.fromString(rs.getString("file_id")),
                        rs.getString("original_name"),
                        rs.getLong("size_bytes"),
                        rs.getString("mime_type"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public record TaskFileRecord(UUID fileId, String fileName, long sizeBytes, String mimeType, Instant createdAt) {}
}
