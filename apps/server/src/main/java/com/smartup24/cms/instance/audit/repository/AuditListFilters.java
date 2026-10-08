package com.smartup24.cms.instance.audit.repository;

import com.smartup24.cms.instance.common.query.QueryPlan;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The flat filters of the audit log and of the security events, and the predicates they add to a registry page of
 * {@code audit.logs} and {@code audit.security_events}. Split from {@link AuditLogRepository}, which writes and maps
 * the rows (plan 10/10, item 3.10).
 */
public final class AuditListFilters {

    private AuditListFilters() {}

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
}
