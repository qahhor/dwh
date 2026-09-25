package com.greenwhite.dwh.instance.report.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The export journal {@code report_exports} (ADR-0018). */
@Repository
public class ReportExportRepository {

    private static final String COLUMNS = """
            id, public_id, user_id, list_code, request::text as request, state, rows_count, truncated, file_name,
            storage_key, size_bytes, error_code, created_at, started_at, finished_at, expires_at""";

    private final JdbcClient jdbc;

    public ReportExportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ExportRow(long id, UUID publicId, long userId, String listCode, String request, String state,
                            Integer rowsCount, boolean truncated, String fileName, String storageKey, Long sizeBytes,
                            String errorCode, Instant createdAt, Instant startedAt, Instant finishedAt,
                            Instant expiresAt) {
    }

    public ExportRow insert(long userId, String listCode, String requestJson) {
        return jdbc.sql("insert into report_exports (user_id, list_code, request) values (:user, :list, cast(:request as jsonb)) "
                        + "returning " + COLUMNS)
                .param("user", userId)
                .param("list", listCode)
                .param("request", requestJson)
                .query(this::map)
                .single();
    }

    public Optional<ExportRow> find(UUID publicId) {
        return jdbc.sql("select " + COLUMNS + " from report_exports where public_id = :id")
                .param("id", publicId)
                .query(this::map)
                .optional();
    }

    public List<ExportRow> listForUser(long userId, int limit) {
        return jdbc.sql("select " + COLUMNS + " from report_exports where user_id = :user order by created_at desc, id desc limit :limit")
                .param("user", userId)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    /** Exports of a person that are still waiting or running. */
    public int countActive(long userId) {
        return jdbc.sql("select count(*) from report_exports where user_id = :user and state in ('queued', 'running')")
                .param("user", userId)
                .query(Integer.class)
                .single();
    }

    /** Takes a queued export for running; false when another worker already took it or it is gone. */
    public boolean markRunning(long id) {
        return jdbc.sql("update report_exports set state = 'running', started_at = now() where id = :id and state = 'queued'")
                .param("id", id)
                .update() == 1;
    }

    public void markDone(long id, int rows, boolean truncated, String fileName, String storageKey, long sizeBytes) {
        jdbc.sql("""
                        update report_exports
                           set state = 'done', rows_count = :rows, truncated = :truncated, file_name = :name,
                               storage_key = :key, size_bytes = :size, finished_at = now()
                         where id = :id
                        """)
                .param("rows", rows)
                .param("truncated", truncated)
                .param("name", fileName)
                .param("key", storageKey)
                .param("size", sizeBytes)
                .param("id", id)
                .update();
    }

    public void markFailed(long id, String errorCode) {
        jdbc.sql("update report_exports set state = 'failed', error_code = :code, finished_at = now() where id = :id")
                .param("code", errorCode)
                .param("id", id)
                .update();
    }

    public List<ExportRow> findExpired(Instant now, int limit) {
        return jdbc.sql("select " + COLUMNS + " from report_exports where expires_at < :now order by expires_at limit :limit")
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    public void delete(long id) {
        jdbc.sql("delete from report_exports where id = :id").param("id", id).update();
    }

    private ExportRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ExportRow(
                rs.getLong("id"),
                rs.getObject("public_id", UUID.class),
                rs.getLong("user_id"),
                rs.getString("list_code"),
                rs.getString("request"),
                rs.getString("state"),
                rs.getObject("rows_count", Integer.class),
                rs.getBoolean("truncated"),
                rs.getString("file_name"),
                rs.getString("storage_key"),
                rs.getObject("size_bytes", Long.class),
                rs.getString("error_code"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("expires_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
