package com.greenwhite.dwh.instance.audit.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class AuditLogRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AuditLogRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void logChange(String tableName, String rowPk, String event, Long changedBy,
                          Long sessionId, boolean isApi, List<String> changedColumns,
                          Map<String, Object> oldRow, Map<String, Object> newRow) {

        String oldRowJson = oldRow != null ? toJson(oldRow) : null;
        String newRowJson = newRow != null ? toJson(newRow) : null;

        jdbcClient.sql("""
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

    public void logSecurityEvent(String eventType, Long userId, String ip, String userAgent, Map<String, Object> details) {
        String detailsJson = toJson(details);

        jdbcClient.sql("""
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

    public List<AuditRecord> listAuditLogs(String tableName, String rowPk, String event, Long userId,
                                           Instant from, Instant to, int limit) {
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

        sql.append(" order by a.changed_at desc limit :limit");

        var query = jdbcClient.sql(sql.toString()).param("limit", limit > 0 ? limit : 50);
        if (tableName != null && !tableName.isBlank()) query.param("tableName", tableName);
        if (rowPk != null && !rowPk.isBlank()) query.param("rowPk", rowPk);
        if (event != null && !event.isBlank()) query.param("event", event);
        if (userId != null) query.param("userId", userId);
        if (from != null) query.param("from", from);
        if (to != null) query.param("to", to);

        return query.query(this::mapAuditRecord).list();
    }

    public List<SecurityEventRecord> listSecurityEvents(String eventType, Long userId, String ip,
                                                       Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("""
                select s.id, s.event_type, s.user_id, host(s.ip) as ip_str, s.user_agent,
                       s.details::text as details_str, s.created_at,
                       u.name as user_name, u.login as user_login
                from security_events s
                left join md_users u on u.id = s.user_id
                where 1=1
                """);

        if (eventType != null && !eventType.isBlank()) {
            sql.append(" and s.event_type = :eventType");
        }
        if (userId != null) {
            sql.append(" and s.user_id = :userId");
        }
        if (ip != null && !ip.isBlank()) {
            sql.append(" and host(s.ip) like :ip");
        }
        if (from != null) {
            sql.append(" and s.created_at >= :from");
        }
        if (to != null) {
            sql.append(" and s.created_at <= :to");
        }

        sql.append(" order by s.created_at desc limit :limit");

        var query = jdbcClient.sql(sql.toString()).param("limit", limit > 0 ? limit : 50);
        if (eventType != null && !eventType.isBlank()) query.param("eventType", eventType);
        if (userId != null) query.param("userId", userId);
        if (ip != null && !ip.isBlank()) query.param("ip", "%" + ip + "%");
        if (from != null) query.param("from", from);
        if (to != null) query.param("to", to);

        return query.query((rs, rowNum) -> new SecurityEventRecord(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getObject("user_id") != null ? rs.getLong("user_id") : null,
                rs.getString("ip_str"),
                rs.getString("user_agent"),
                parseJson(rs.getString("details_str")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("user_name"),
                rs.getString("user_login")
        )).list();
    }

    public AuditStats getAuditStats() {
        long totalLogs = jdbcClient.sql("select count(*) from audit_log").query(Long.class).single();
        long totalSecEvents = jdbcClient.sql("select count(*) from security_events").query(Long.class).single();
        long secEvents24h = jdbcClient.sql("select count(*) from security_events where created_at >= now() - interval '24 hours'").query(Long.class).single();
        long failedLogins24h = jdbcClient.sql("select count(*) from security_events where event_type in ('LOGIN_FAILED', 'LOGIN_LOCKED', 'IP_RATE_LIMITED') and created_at >= now() - interval '24 hours'").query(Long.class).single();

        return new AuditStats(totalLogs, totalSecEvents, secEvents24h, failedLogins24h);
    }

    private AuditRecord mapAuditRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String[] arr = rs.getArray("changed_columns") != null ? (String[]) rs.getArray("changed_columns").getArray() : null;
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
                parseJson(rs.getString("old_str")),
                parseJson(rs.getString("new_str")),
                rs.getString("changed_by_name"),
                rs.getString("changed_by_login")
        );
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
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
            String changedByLogin
    ) {}

    public record SecurityEventRecord(
            Long id,
            String eventType,
            Long userId,
            String ip,
            String userAgent,
            Map<String, Object> details,
            Instant createdAt,
            String userName,
            String userLogin
    ) {}

    public record AuditStats(
            long totalAuditLogs,
            long totalSecurityEvents,
            long securityEventsLast24h,
            long failedLoginsLast24h
    ) {}
}

