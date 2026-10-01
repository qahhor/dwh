package com.smartup24.cms.instance.warehouse.repository;

import com.smartup24.cms.instance.warehouse.api.WarehouseLoad;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The load ledger in the OLTP database: {@code fnd_loads} (one row per load version, its {@code id} tags the rows in
 * pg-dwh) and the package log {@code fnd_load_log} (V104, V106; plan 10/10, item 4.2).
 */
@Repository
public class LoadRepository {

    private static final String LOAD_COLUMNS = "id, source_code, package_ref, period_from, period_to, format_version,"
            + " applied_at, applied_by, rows_total, rows_accepted, rows_rejected, status, superseded_by";

    private final JdbcClient jdbc;

    public LoadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a load version in {@code pending}; returns its id. */
    public long insertPending(
            String sourceCode, UUID packageRef, LocalDate periodFrom, LocalDate periodTo, String formatVersion) {
        return jdbc.sql("""
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
                .single();
    }

    /** Moves a pending load to {@code applied} with its counters; returns the rows updated. */
    public int markApplied(long loadId, int rowsTotal, int rowsAccepted, int rowsRejected, String appliedBy) {
        return jdbc.sql("""
                        update fnd_loads
                           set status = 'applied', applied_at = now(), applied_by = :actor,
                               rows_total = :total, rows_accepted = :accepted, rows_rejected = :rejected
                         where id = :id and status = 'pending'
                        """)
                .param("actor", appliedBy)
                .param("total", rowsTotal)
                .param("accepted", rowsAccepted)
                .param("rejected", rowsRejected)
                .param("id", loadId)
                .update();
    }

    /** Supersedes the other applied load of the same source and period by the given one. */
    public void supersedeOthers(long loadId, String sourceCode, LocalDate periodFrom, LocalDate periodTo) {
        jdbc.sql("""
                        update fnd_loads
                           set status = 'superseded', superseded_by = :id
                         where id <> :id and status = 'applied'
                           and source_code = :source and period_from = :from and period_to = :to
                        """)
                .param("id", loadId)
                .param("source", sourceCode)
                .param("from", periodFrom)
                .param("to", periodTo)
                .update();
    }

    /** Moves a pending load to {@code failed}; returns the rows updated. */
    public int markFailed(long loadId) {
        return jdbc.sql("update fnd_loads set status = 'failed' where id = :id and status = 'pending'")
                .param("id", loadId)
                .update();
    }

    /** The applied or superseded load of a package, which its log rows refer to once it exists. */
    public Optional<Long> appliedLoadId(UUID packageRef) {
        return jdbc.sql("select id from fnd_loads where package_ref = :package"
                        + " and status in ('applied', 'superseded')")
                .param("package", packageRef)
                .query(Long.class)
                .optional();
    }

    /** Appends a row to the package log. */
    public void insertLog(
            UUID packageRef,
            Long loadId,
            String event,
            String fromStatus,
            String toStatus,
            String actor,
            String note,
            String fileSha) {
        jdbc.sql("""
                        insert into fnd_load_log (package_ref, load_id, event, from_status, to_status,
                                                  actor, note, file_sha)
                        values (:package, :load, :event, :from, :to, :actor, :note, :sha)
                        """)
                .param("package", packageRef)
                .param("load", loadId)
                .param("event", event)
                .param("from", fromStatus)
                .param("to", toStatus)
                .param("actor", actor)
                .param("note", note)
                .param("sha", fileSha)
                .update();
    }

    /** The applied loads of a source, in id order. */
    public List<Long> appliedLoadIds(String sourceCode) {
        return jdbc.sql("select id from fnd_loads where source_code = :source and status = 'applied' order by id")
                .param("source", sourceCode)
                .query(Long.class)
                .list();
    }

    /** A load version by its id. */
    public Optional<WarehouseLoad> find(long loadId) {
        return jdbc.sql("select " + LOAD_COLUMNS + " from fnd_loads where id = :id")
                .param("id", loadId)
                .query(LoadRepository::mapLoad)
                .optional();
    }

    /** A load version under a row lock ({@code for update}): a concurrent transition waits for the commit. */
    public Optional<WarehouseLoad> lock(long loadId) {
        return jdbc.sql("select " + LOAD_COLUMNS + " from fnd_loads where id = :id for update")
                .param("id", loadId)
                .query(LoadRepository::mapLoad)
                .optional();
    }

    /**
     * The status of a load under a shared lock ({@code for share}), held until the caller's transaction ends: a
     * transition ({@code for update}) waits for it.
     */
    public Optional<String> shareStatus(long loadId) {
        return jdbc.sql("select status from fnd_loads where id = :id for share")
                .param("id", loadId)
                .query(String.class)
                .optional();
    }

    /** The loads left in status {@code failed}, whose raw rows the cleanup removes. */
    public List<Long> failedLoadIds() {
        return jdbc.sql("select id from fnd_loads where status = 'failed'")
                .query(Long.class)
                .list();
    }

    /** Which of the ids are loads: one query for all of them, never one per id. */
    public Set<Long> existingIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select id from fnd_loads where id = any(:ids)")
                .param("ids", ids.toArray(Long[]::new))
                .query(Long.class)
                .list());
    }

    private static WarehouseLoad mapLoad(ResultSet rs, int rowNum) throws SQLException {
        Timestamp appliedAt = rs.getTimestamp("applied_at");
        Number supersededBy = (Number) rs.getObject("superseded_by");
        return new WarehouseLoad(
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
