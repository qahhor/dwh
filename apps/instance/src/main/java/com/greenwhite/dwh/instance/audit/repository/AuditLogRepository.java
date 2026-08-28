package com.greenwhite.dwh.instance.audit.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public List<AuditRecord> listAuditLogs(String tableName, String rowPk, Long userId, int limit) {
        StringBuilder sql = new StringBuilder("""
                select id, table_name, row_pk, event, changed_by, session_id, is_api,
                       changed_at, changed_columns, old_row::text as old_str, new_row::text as new_str
                from audit_log
                where 1=1
                """);

        if (tableName != null && !tableName.isBlank()) {
            sql.append(" and table_name = :tableName");
        }
        if (rowPk != null && !rowPk.isBlank()) {
            sql.append(" and row_pk = :rowPk");
        }
        if (userId != null) {
            sql.append(" and changed_by = :userId");
        }

        sql.append(" order by changed_at desc limit :limit");

        var query = jdbcClient.sql(sql.toString()).param("limit", limit);
        if (tableName != null && !tableName.isBlank()) query.param("tableName", tableName);
        if (rowPk != null && !rowPk.isBlank()) query.param("rowPk", rowPk);
        if (userId != null) query.param("userId", userId);

        return query.query(this::mapAuditRecord).list();
    }

    public List<SecurityEventRecord> listSecurityEvents(int limit) {
        return jdbcClient.sql("""
                select id, event_type, user_id, host(ip) as ip_str, user_agent,
                       details::text as details_str, created_at
                from security_events
                order by created_at desc
                limit :limit
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> new SecurityEventRecord(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getObject("user_id") != null ? rs.getLong("user_id") : null,
                        rs.getString("ip_str"),
                        rs.getString("user_agent"),
                        parseJson(rs.getString("details_str")),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
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
                parseJson(rs.getString("new_str"))
        );
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
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
            Map<String, Object> newRow
    ) {}

    public record SecurityEventRecord(
            Long id,
            String eventType,
            Long userId,
            String ip,
            String userAgent,
            Map<String, Object> details,
            Instant createdAt
    ) {}
}
