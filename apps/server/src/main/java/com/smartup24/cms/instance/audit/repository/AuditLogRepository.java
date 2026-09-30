package com.smartup24.cms.instance.audit.repository;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryPlan;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AuditLogRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final JsonColumns jsonColumns;

    public AuditLogRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.jsonColumns = new JsonColumns(objectMapper, "audit_log");
    }

    public void logChange(
            String tableName,
            String rowPk,
            String event,
            Long changedBy,
            Long sessionId,
            boolean isApi,
            List<String> changedColumns,
            Map<String, Object> oldRow,
            Map<String, Object> newRow) {

        String oldRowJson = oldRow != null ? jsonColumns.object(oldRow) : null;
        String newRowJson = newRow != null ? jsonColumns.object(newRow) : null;

        jdbcClient
                .sql("""
                insert into audit_log (table_name, row_pk, event, changed_by, session_id,
                                      is_api, changed_at, changed_columns, old_row, new_row)
                values (:tableName, :rowPk, :event, :changedBy, :sessionId,
                        :isApi, now(), :changedColumns, cast(:oldRow as jsonb), cast(:newRow as jsonb))
                """)
                .param("tableName", tableName)
                .param("rowPk", rowPk)
                .param("event", event)
                .param("changedBy", changedBy)
                .param("sessionId", sessionId)
                .param("isApi", isApi)
                .param("changedColumns", changedColumns != null ? changedColumns.toArray(new String[0]) : null)
                .param("oldRow", oldRowJson)
                .param("newRow", newRowJson)
                .update();
    }

    public void logSecurityEvent(
            String eventType, Long userId, String ip, String userAgent, Map<String, Object> details) {
        String detailsJson = jsonColumns.object(details);

        jdbcClient
                .sql("""
                insert into security_events (event_type, user_id, ip, user_agent, details, created_at)
                values (:eventType, :userId, cast(:ip as inet), :userAgent, cast(:details as jsonb), now())
                """)
                .param("eventType", eventType)
                .param("userId", userId)
                .param("ip", ip != null ? ip : "127.0.0.1")
                .param("userAgent", userAgent)
                .param("details", detailsJson)
                .update();
    }

    public List<AuditRecord> listAuditLogs(
            String tableName,
            String rowPk,
            String event,
            Long userId,
            Instant from,
            Instant to,
            Instant cursorChangedAt,
            Long cursorId,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                select a.id, a.table_name, a.row_pk, a.event, a.changed_by, a.session_id, a.is_api,
                       a.changed_at, a.changed_columns, a.old_row::text as old_str, a.new_row::text as new_str,
                       u.name as changed_by_name, u.login as changed_by_login
                from audit_log a
                left join md_users u on u.id = a.changed_by
                where 1=1
                """);

        if (tableName != null && !tableName.isBlank()) {
            sql.append(" and a.table_name = :tableName");
        }
        if (rowPk != null && !rowPk.isBlank()) {
            sql.append(" and a.row_pk = :rowPk");
        }
        if (event != null && !event.isBlank()) {
            sql.append(" and a.event = :event");
        }
        if (userId != null) {
            sql.append(" and a.changed_by = :userId");
        }
        if (from != null) {
            sql.append(" and a.changed_at >= :from");
        }
        if (to != null) {
            sql.append(" and a.changed_at <= :to");
        }
        if (cursorChangedAt != null && cursorId != null) {
            sql.append(
                    " and (a.changed_at < :cursorChangedAt or (a.changed_at = :cursorChangedAt and a.id < :cursorId))");
        }

        sql.append(" order by a.changed_at desc, a.id desc limit :limit");

        var query = jdbcClient.sql(sql.toString()).param("limit", limit > 0 ? limit : 50);
        if (tableName != null && !tableName.isBlank()) query.param("tableName", tableName);
        if (rowPk != null && !rowPk.isBlank()) query.param("rowPk", rowPk);
        if (event != null && !event.isBlank()) query.param("event", event);
        if (userId != null) query.param("userId", userId);
        if (from != null) query.param("from", java.sql.Timestamp.from(from));
        if (to != null) query.param("to", java.sql.Timestamp.from(to));
        if (cursorChangedAt != null && cursorId != null) {
            query.param("cursorChangedAt", java.sql.Timestamp.from(cursorChangedAt))
                    .param("cursorId", cursorId);
        }

        return query.query(this::mapAuditRecord).list();
    }

    /** Columns of an audit row as {@link #mapAuditRecord} reads them; the registry list {@code audit.logs} selects them. */
    public static final String LOG_COLUMNS = """
            a.id, a.table_name, a.row_pk, a.event, a.changed_by, a.session_id, a.is_api,
            a.changed_at, a.changed_columns, a.old_row::text as old_str, a.new_row::text as new_str,
            u.name as changed_by_name, u.login as changed_by_login""";

    /** Below this many rows (by the statistics) a total is counted; above, it is the estimate. */
    static final long COUNT_BELOW = 100_000;

    public static final String LOG_FROM = "audit_log a left join md_users u on u.id = a.changed_by";

    /** Columns of a security event as {@link #mapSecurityEvent} reads them ({@code audit.security_events}). */
    public static final String SECURITY_COLUMNS = """
            s.id, s.event_type, s.user_id, host(s.ip) as ip_str, s.user_agent,
            s.details::text as details_str, s.created_at,
            u.name as user_name, u.login as user_login""";

    public static final String SECURITY_FROM = "security_events s left join md_users u on u.id = s.user_id";

    /** The flat filters the audit log took before the registry, kept for existing callers. */
    public record AuditLogFilters(String tableName, String rowPk, String event, Long userId, Instant from, Instant to) {

        public static AuditLogFilters none() {
            return new AuditLogFilters(null, null, null, null, null, null);
        }

        /** Canonical form for the cursor fingerprint; null when no filter is set. */
        public String canonical() {
            String value = part("table", tableName)
                    + part("row", rowPk)
                    + part("event", event)
                    + (userId == null ? "" : ";user=" + userId)
                    + (from == null ? "" : ";from=" + from)
                    + (to == null ? "" : ";to=" + to);
            return value.isEmpty() ? null : value;
        }
    }

    /** The flat filters the security events took before the registry, kept for existing callers. */
    public record SecurityEventFilters(String eventType, Long userId, String ip, Instant from, Instant to) {

        public static SecurityEventFilters none() {
            return new SecurityEventFilters(null, null, null, null, null);
        }

        public String canonical() {
            String value = part("type", eventType)
                    + (userId == null ? "" : ";user=" + userId)
                    + part("ip", ip)
                    + (from == null ? "" : ";from=" + from)
                    + (to == null ? "" : ";to=" + to);
            return value.isEmpty() ? null : value;
        }
    }

    private static String part(String name, String value) {
        return value == null || value.isBlank() ? "" : ";" + name + "=" + value.length() + ":" + value;
    }

    /** The flat audit filters as the registry page's extra predicate. */
    public static QueryPlan.SqlFragment logPredicate(AuditLogFilters filters) {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        if (filters.tableName() != null && !filters.tableName().isBlank()) {
            sql.append(" and a.table_name = :tableName");
            params.put("tableName", filters.tableName());
        }
        if (filters.rowPk() != null && !filters.rowPk().isBlank()) {
            sql.append(" and a.row_pk = :rowPk");
            params.put("rowPk", filters.rowPk());
        }
        if (filters.event() != null && !filters.event().isBlank()) {
            sql.append(" and a.event = :event");
            params.put("event", filters.event());
        }
        if (filters.userId() != null) {
            sql.append(" and a.changed_by = :userId");
            params.put("userId", filters.userId());
        }
        if (filters.from() != null) {
            sql.append(" and a.changed_at >= :from");
            params.put("from", java.sql.Timestamp.from(filters.from()));
        }
        if (filters.to() != null) {
            sql.append(" and a.changed_at <= :to");
            params.put("to", java.sql.Timestamp.from(filters.to()));
        }
        return new QueryPlan.SqlFragment(sql.toString(), params);
    }

    /** The flat security-event filters as the registry page's extra predicate. */
    public static QueryPlan.SqlFragment securityPredicate(SecurityEventFilters filters) {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        if (filters.eventType() != null && !filters.eventType().isBlank()) {
            sql.append(" and s.event_type = :eventType");
            params.put("eventType", filters.eventType());
        }
        if (filters.userId() != null) {
            sql.append(" and s.user_id = :userId");
            params.put("userId", filters.userId());
        }
        if (filters.ip() != null && !filters.ip().isBlank()) {
            sql.append(" and host(s.ip) like :ip");
            params.put("ip", "%" + filters.ip() + "%");
        }
        if (filters.from() != null) {
            sql.append(" and s.created_at >= :from");
            params.put("from", java.sql.Timestamp.from(filters.from()));
        }
        if (filters.to() != null) {
            sql.append(" and s.created_at <= :to");
            params.put("to", java.sql.Timestamp.from(filters.to()));
        }
        return new QueryPlan.SqlFragment(sql.toString(), params);
    }

    /** Reads a row of {@link #SECURITY_COLUMNS}. */
    public SecurityEventRecord mapSecurityEvent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SecurityEventRecord(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getObject("user_id") != null ? rs.getLong("user_id") : null,
                rs.getString("ip_str"),
                rs.getString("user_agent"),
                jsonColumns.readObject(rs.getString("details_str")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("user_name"),
                rs.getString("user_login"));
    }

    public long countAuditLogs(String tableName, String rowPk, String event, Long userId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("select count(*) from audit_log a where 1=1");
        if (tableName != null && !tableName.isBlank()) sql.append(" and a.table_name = :tableName");
        if (rowPk != null && !rowPk.isBlank()) sql.append(" and a.row_pk = :rowPk");
        if (event != null && !event.isBlank()) sql.append(" and a.event = :event");
        if (userId != null) sql.append(" and a.changed_by = :userId");
        if (from != null) sql.append(" and a.changed_at >= :from");
        if (to != null) sql.append(" and a.changed_at <= :to");

        var query = jdbcClient.sql(sql.toString());
        if (tableName != null && !tableName.isBlank()) query.param("tableName", tableName);
        if (rowPk != null && !rowPk.isBlank()) query.param("rowPk", rowPk);
        if (event != null && !event.isBlank()) query.param("event", event);
        if (userId != null) query.param("userId", userId);
        if (from != null) query.param("from", java.sql.Timestamp.from(from));
        if (to != null) query.param("to", java.sql.Timestamp.from(to));
        return query.query(Long.class).single();
    }

    /** Audit screen totals: counted while small, PostgreSQL statistics once large (item 3.5); last day exact. */
    public AuditStats getAuditStats() {
        long totalLogs = rows(
                "audit_log",
                "select coalesce(sum(greatest(reltuples, 0)), 0)::bigint from pg_class where oid in"
                        + " (select inhrelid from pg_inherits where inhparent = 'audit_log'::regclass)");
        long totalSecurity = rows(
                "security_events",
                "select greatest(reltuples, 0)::bigint from pg_class where oid = 'security_events'::regclass");
        var secStats = jdbcClient
                .sql("""
                select
                    count(*) as sec_24h,
                    count(*) filter (where event_type in ('LOGIN_FAILED', 'LOGIN_LOCKED', 'IP_RATE_LIMITED'))
                        as failed_24h
                from security_events
                where created_at >= now() - interval '24 hours'
                """)
                .query((rs, rowNum) -> new long[] {rs.getLong("sec_24h"), rs.getLong("failed_24h")})
                .single();

        return new AuditStats(totalLogs, totalSecurity, secStats[0], secStats[1], Instant.now());
    }

    /** Rows of a table: its statistics once they pass {@link #COUNT_BELOW}, a count before. */
    private long rows(String table, String estimateSql) {
        long estimate = jdbcClient.sql(estimateSql).query(Long.class).single();
        return estimate >= COUNT_BELOW
                ? estimate
                : jdbcClient
                        .sql("select count(*) from " + table)
                        .query(Long.class)
                        .single();
    }

    /** Reads a row of {@link #LOG_COLUMNS}. */
    public AuditRecord mapAuditRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String[] arr = rs.getArray("changed_columns") != null
                ? (String[]) rs.getArray("changed_columns").getArray()
                : null;
        List<String> columns = arr != null ? List.of(arr) : List.of();

        return new AuditRecord(
                rs.getLong("id"),
                rs.getString("table_name"),
                rs.getString("row_pk"),
                rs.getString("event"),
                rs.getObject("changed_by") != null ? rs.getLong("changed_by") : null,
                rs.getObject("session_id") != null ? rs.getLong("session_id") : null,
                rs.getBoolean("is_api"),
                rs.getTimestamp("changed_at").toInstant(),
                columns,
                jsonColumns.readObject(rs.getString("old_str")),
                jsonColumns.readObject(rs.getString("new_str")),
                rs.getString("changed_by_name"),
                rs.getString("changed_by_login"));
    }

    public record AuditRecord(
            Long id,
            String tableName,
            String rowPk,
            String event,
            Long changedBy,
            Long sessionId,
            boolean isApi,
            Instant changedAt,
            List<String> changedColumns,
            Map<String, Object> oldRow,
            Map<String, Object> newRow,
            String changedByName,
            String changedByLogin) {}

    public record SecurityEventRecord(
            Long id,
            String eventType,
            Long userId,
            String ip,
            String userAgent,
            Map<String, Object> details,
            Instant createdAt,
            String userName,
            String userLogin) {}

    public record AuditStats(
            long totalAuditLogs,
            long totalSecurityEvents,
            long securityEventsLast24h,
            long failedLoginsLast24h,
            Instant computedAt) {
        public AuditStats(
                long totalAuditLogs, long totalSecurityEvents, long securityEventsLast24h, long failedLoginsLast24h) {
            this(totalAuditLogs, totalSecurityEvents, securityEventsLast24h, failedLoginsLast24h, Instant.now());
        }
    }
}
