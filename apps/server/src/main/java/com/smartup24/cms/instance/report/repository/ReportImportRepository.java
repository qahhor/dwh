package com.smartup24.cms.instance.report.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The import journal {@code report_imports} and its row errors {@code report_import_errors} (ADR-0032, 10.1). */
@Repository
public class ReportImportRepository {

    private static final String COLUMNS = """
            id, public_id, user_id, entity_code, file_id, mode, lang, state, rows_total, rows_done, created_count,
            updated_count, failed_count, error_code, report_key, report_size, created_at, started_at, finished_at,
            expires_at""";

    private final JdbcClient jdbc;

    public ReportImportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A journal row of an import. */
    public record ImportRow(
            long id,
            UUID publicId,
            long userId,
            String entityCode,
            UUID fileId,
            String mode,
            String lang,
            String state,
            Integer rowsTotal,
            int rowsDone,
            int createdCount,
            int updatedCount,
            int failedCount,
            String errorCode,
            String reportKey,
            Long reportSize,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant expiresAt) {}

    /** A stored problem of a row. */
    public record ErrorRow(int rowNo, String field, String code, String message) {}

    public ImportRow insert(long userId, String entityCode, UUID fileId, String mode, String lang) {
        return jdbc.sql("insert into report_imports (user_id, entity_code, file_id, mode, lang)"
                        + " values (:user, :entity, :file, :mode, :lang) returning " + COLUMNS)
                .param("user", userId)
                .param("entity", entityCode)
                .param("file", fileId)
                .param("mode", mode)
                .param("lang", lang)
                .query(this::map)
                .single();
    }

    public Optional<ImportRow> find(UUID publicId) {
        return jdbc.sql("select " + COLUMNS + " from report_imports where public_id = :id")
                .param("id", publicId)
                .query(this::map)
                .optional();
    }

    /** Imports of a person that are still waiting or running. */
    public int countActive(long userId) {
        return jdbc.sql("select count(*) from report_imports where user_id = :user and state in ('queued', 'running')")
                .param("user", userId)
                .query(Integer.class)
                .single();
    }

    /**
     * Takes an import for running: a queued one, or a running one whose job is retried after a failure (it goes on
     * after its checkpoint). False when it is finished or gone.
     */
    public boolean markRunning(long id) {
        return jdbc.sql("""
                                update report_imports
                                   set state = 'running', started_at = coalesce(started_at, clock_timestamp())
                                 where id = :id and state in ('queued', 'running')
                                """).param("id", id).update() == 1;
    }

    public void total(long id, int rows) {
        jdbc.sql("update report_imports set rows_total = :rows where id = :id")
                .param("rows", rows)
                .param("id", id)
                .update();
    }

    /** Moves the checkpoint past a batch and adds its counters. */
    public void progress(long id, int rowsDone, int created, int updated, int failed) {
        jdbc.sql("""
                        update report_imports
                           set rows_done = :done, created_count = created_count + :created,
                               updated_count = updated_count + :updated, failed_count = failed_count + :failed
                         where id = :id
                        """)
                .param("done", rowsDone)
                .param("created", created)
                .param("updated", updated)
                .param("failed", failed)
                .param("id", id)
                .update();
    }

    public void addErrors(long id, List<ErrorRow> errors) {
        for (ErrorRow error : errors) {
            jdbc.sql("insert into report_import_errors (import_id, row_no, field, code, message)"
                            + " values (:id, :row, :field, :code, :message)")
                    .param("id", id)
                    .param("row", error.rowNo())
                    .param("field", error.field())
                    .param("code", error.code())
                    .param("message", error.message())
                    .update();
        }
    }

    /** The first stored problems of an import, by row. */
    public List<ErrorRow> errors(long id, int limit) {
        return jdbc.sql("select row_no, field, code, message from report_import_errors where import_id = :id"
                        + " order by row_no, id limit :limit")
                .param("id", id)
                .param("limit", limit)
                .query((rs, n) -> new ErrorRow(
                        rs.getInt("row_no"), rs.getString("field"), rs.getString("code"), rs.getString("message")))
                .list();
    }

    /** The stored problems of the rows from {@code fromRow} to {@code toRow}, by row: a slice of the report. */
    public List<ErrorRow> errors(long id, int fromRow, int toRow) {
        return jdbc.sql("select row_no, field, code, message from report_import_errors where import_id = :id"
                        + " and row_no between :from and :to order by row_no, id")
                .param("id", id)
                .param("from", fromRow)
                .param("to", toRow)
                .query((rs, n) -> new ErrorRow(
                        rs.getInt("row_no"), rs.getString("field"), rs.getString("code"), rs.getString("message")))
                .list();
    }

    public void markDone(long id, String reportKey, Long reportSize) {
        jdbc.sql("""
                        update report_imports
                           set state = 'done', report_key = :key, report_size = :size, finished_at = clock_timestamp()
                         where id = :id
                        """)
                .param("key", reportKey)
                .param("size", reportSize)
                .param("id", id)
                .update();
    }

    public void markFailed(long id, String errorCode) {
        jdbc.sql("update report_imports set state = 'failed', error_code = :code, finished_at = clock_timestamp()"
                        + " where id = :id")
                .param("code", errorCode)
                .param("id", id)
                .update();
    }

    public List<ImportRow> findExpired(Instant now, int limit) {
        return jdbc.sql("select " + COLUMNS
                        + " from report_imports where expires_at < :now order by expires_at limit :limit")
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    public void delete(long id) {
        jdbc.sql("delete from report_imports where id = :id").param("id", id).update();
    }

    private ImportRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ImportRow(
                rs.getLong("id"),
                rs.getObject("public_id", UUID.class),
                rs.getLong("user_id"),
                rs.getString("entity_code"),
                rs.getObject("file_id", UUID.class),
                rs.getString("mode"),
                rs.getString("lang"),
                rs.getString("state"),
                rs.getObject("rows_total", Integer.class),
                rs.getInt("rows_done"),
                rs.getInt("created_count"),
                rs.getInt("updated_count"),
                rs.getInt("failed_count"),
                rs.getString("error_code"),
                rs.getString("report_key"),
                rs.getObject("report_size", Long.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("expires_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
