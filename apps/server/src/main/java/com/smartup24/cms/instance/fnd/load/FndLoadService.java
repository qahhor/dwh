package com.smartup24.cms.instance.fnd.load;

import com.smartup24.cms.instance.fnd.FndActor;
import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.error.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.error.ConstraintViolationException;
import com.smartup24.cms.instance.fnd.error.FndSqlErrors;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Load versions and the package log.
 *
 * <p>Apply protocol: {@link #begin} creates a version in {@code pending}; the {@code FndRawWriter} facade then writes
 * rows into pg-dwh under that {@code load_id}; {@link #apply} moves the version to {@code applied} and supersedes the
 * previous load of the same source for the same period. On failure, {@link #fail} is called: the rows stay, and the
 * maintenance job {@code fnd.load_cleanup} deletes them. Allowed transitions: {@code pending→applied},
 * {@code pending→failed}, {@code applied→superseded}.
 */
@Service
public class FndLoadService {

    private final JdbcClient jdbc;
    private final FndActors actors;

    public FndLoadService(JdbcClient jdbc, FndActors actors) {
        this.jdbc = jdbc;
        this.actors = actors;
    }

    /** Opens a load version. A repeated {@code package_ref} is rejected by {@code fnd_loads_uk_package_ref}. */
    @Transactional
    public long begin(
            String sourceCode,
            UUID packageRef,
            LocalDate periodFrom,
            LocalDate periodTo,
            String formatVersion,
            FndActor actor) {
        actors.apply(actor);
        return FndSqlErrors.translating(() -> jdbc.sql("""
                        insert into fnd_loads (source_code, package_ref, period_from, period_to,
                                               format_version, status)
                        values (:source, :package, :from, :to, :format, 'pending')
                        returning id
                        """)
                .param("source", sourceCode)
                .param("package", packageRef)
                .param("from", periodFrom)
                .param("to", periodTo)
                .param("format", formatVersion)
                .query(Long.class)
                .single());
    }

    /**
     * Applies a load. The row counters must add up ({@code accepted + rejected = total}), otherwise the
     * {@code fnd_loads_ck_rows} constraint rejects the update. The previously applied load of the same source for the
     * same period becomes {@code superseded} and gets a {@code superseded_by} reference to this one.
     */
    @Transactional
    public void apply(long loadId, int rowsTotal, int rowsAccepted, int rowsRejected, FndActor actor) {
        FndLoad load = lockPending(loadId);
        actors.apply(actor);
        int updated = FndSqlErrors.translating(() -> jdbc.sql("""
                        update fnd_loads
                           set status = 'applied', applied_at = now(), applied_by = :actor,
                               rows_total = :total, rows_accepted = :accepted, rows_rejected = :rejected
                         where id = :id and status = 'pending'
                        """)
                .param("actor", actor.name())
                .param("total", rowsTotal)
                .param("accepted", rowsAccepted)
                .param("rejected", rowsRejected)
                .param("id", loadId)
                .update());
        requireUpdated(updated);
        FndSqlErrors.translating(() -> jdbc.sql("""
                        update fnd_loads
                           set status = 'superseded', superseded_by = :id
                         where id <> :id and status = 'applied'
                           and source_code = :source and period_from = :from and period_to = :to
                        """)
                .param("id", loadId)
                .param("source", load.sourceCode())
                .param("from", load.periodFrom())
                .param("to", load.periodTo())
                .update());
    }

    /** Marks a load failed and writes the reason to the log; a call without a reason is rejected. */
    @Transactional
    public void fail(long loadId, String reason, FndActor actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Причина сбоя не задана: загрузка без причины не отмечается");
        }
        FndLoad load = lockPending(loadId);
        actors.apply(actor);
        int updated = FndSqlErrors.translating(
                () -> jdbc.sql("update fnd_loads set status = 'failed'" + " where id = :id and status = 'pending'")
                        .param("id", loadId)
                        .update());
        requireUpdated(updated);
        log(load.packageRef(), "failed", FndLoad.PENDING, FndLoad.FAILED, actor, reason, null);
    }

    /**
     * Writes a package log row. {@code load_id} is filled in once the load version has been applied; before that the
     * log is keyed by {@code package_ref}. The {@code fnd_load_log_append_only()} trigger forbids updating or deleting
     * log rows.
     */
    @Transactional
    public void log(
            UUID packageRef,
            String event,
            String fromStatus,
            String toStatus,
            FndActor actor,
            String note,
            String fileSha) {
        if (actor == null) {
            throw new ConstraintViolationException(ConstraintErrorCode.AUDIT_ACTOR_MISSING);
        }
        actors.apply(actor);
        Long loadId = jdbc.sql("select id from fnd_loads where package_ref = :package"
                        + " and status in ('applied', 'superseded')")
                .param("package", packageRef)
                .query(Long.class)
                .optional()
                .orElse(null);
        FndSqlErrors.translating(() -> jdbc.sql("""
                        insert into fnd_load_log (package_ref, load_id, event, from_status, to_status,
                                                  actor, note, file_sha)
                        values (:package, :load, :event, :from, :to, :actor, :note, :sha)
                        """)
                .param("package", packageRef)
                .param("load", loadId)
                .param("event", event)
                .param("from", fromStatus)
                .param("to", toStatus)
                .param("actor", actor.name())
                .param("note", note)
                .param("sha", fileSha)
                .update());
    }

    /** The current data versions of a source: applied loads only. */
    @Transactional(readOnly = true)
    public List<Long> appliedLoadIds(String sourceCode) {
        return jdbc.sql("select id from fnd_loads where source_code = :source and status = 'applied' order by id")
                .param("source", sourceCode)
                .query(Long.class)
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<FndLoad> find(long loadId) {
        return jdbc.sql("select id, source_code, package_ref, period_from, period_to, format_version,"
                        + " applied_at, applied_by, rows_total, rows_accepted, rows_rejected, status, superseded_by"
                        + " from fnd_loads where id = :id")
                .param("id", loadId)
                .query(FndLoadService::mapLoad)
                .optional();
    }

    /**
     * Reads a load under a row lock ({@code for update}) and requires the {@code pending} status. A concurrent
     * {@code apply}/{@code fail} of the same load waits for the commit and then sees the new status; a row write still
     * in progress ({@code FndRawWriter.write} holds {@code for share}) is also waited for.
     */
    private FndLoad lockPending(long loadId) {
        FndLoad load = jdbc.sql("select id, source_code, package_ref, period_from, period_to, format_version,"
                        + " applied_at, applied_by, rows_total, rows_accepted, rows_rejected, status, superseded_by"
                        + " from fnd_loads where id = :id for update")
                .param("id", loadId)
                .query(FndLoadService::mapLoad)
                .optional()
                .orElseThrow(() -> new ConstraintViolationException(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION));
        if (!FndLoad.PENDING.equals(load.status())) {
            throw new ConstraintViolationException(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION);
        }
        return load;
    }

    /** A status transition is one UPDATE conditioned on the current status: 0 rows means the state already moved on. */
    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new ConstraintViolationException(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION);
        }
    }

    private static FndLoad mapLoad(ResultSet rs, int rowNum) throws SQLException {
        Timestamp appliedAt = rs.getTimestamp("applied_at");
        Number supersededBy = (Number) rs.getObject("superseded_by");
        return new FndLoad(
                rs.getLong("id"),
                rs.getString("source_code"),
                rs.getObject("package_ref", UUID.class),
                rs.getDate("period_from").toLocalDate(),
                rs.getDate("period_to").toLocalDate(),
                rs.getString("format_version"),
                appliedAt == null ? null : appliedAt.toInstant(),
                rs.getString("applied_by"),
                integer(rs, "rows_total"),
                integer(rs, "rows_accepted"),
                integer(rs, "rows_rejected"),
                rs.getString("status"),
                supersededBy == null ? null : supersededBy.longValue());
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.intValue();
    }
}
