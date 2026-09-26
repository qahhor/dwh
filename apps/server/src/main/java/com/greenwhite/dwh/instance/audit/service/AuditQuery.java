package com.greenwhite.dwh.instance.audit.service;

import com.greenwhite.dwh.instance.audit.pref.AuditPref;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The audit log and the security events in the field registry (ADR-0016, roadmap item 50):
 * {@code GET /api/v1/audit/logs}, {@code /audit/security-events} and their {@code query-meta}. Newest first, as
 * before. Only the event time sorts: the audit log is partitioned by it, and sorting a large log by an
 * unindexed column would scan every partition.
 */
@Configuration
public class AuditQuery {

    public static final QueryList LOGS = new QueryList(
            "audit.logs",
            AuditPref.FORM_AUDIT_LOG,
            "view",
            AuditLogRepository.LOG_COLUMNS,
            AuditLogRepository.LOG_FROM,
            "a.id",
            List.of(
                    QueryField.of("id", "audit.col.id", QueryFieldType.NUMBER, "a.id"),
                    QueryField.of("tableName", "audit.col.table", QueryFieldType.TEXT, "a.table_name"),
                    QueryField.of("rowPk", "audit.col.row", QueryFieldType.TEXT, "a.row_pk"),
                    QueryField.enumeration("event", "audit.col.event", "a.event", List.of("I", "U", "D"),
                            "audit.event."),
                    QueryField.of("changedByName", "audit.col.changed_by", QueryFieldType.TEXT, "u.name")
                            .asNullable().asSearchable(),
                    QueryField.of("changedBy", "audit.col.changed_by_id", QueryFieldType.NUMBER, "a.changed_by")
                            .asNullable().asHidden(),
                    QueryField.of("isApi", "audit.col.channel", QueryFieldType.BOOLEAN, "a.is_api"),
                    QueryField.of("changedAt", "audit.col.changed_at", QueryFieldType.INSTANT, "a.changed_at")
                            .asSortable()),
            "changedAt",
            true,
            QueryList.DEFAULT_LIMIT,
            QueryList.MAX_LIMIT);

    public static final QueryList SECURITY_EVENTS = new QueryList(
            "audit.security_events",
            AuditPref.FORM_AUDIT_LOG,
            "view",
            AuditLogRepository.SECURITY_COLUMNS,
            AuditLogRepository.SECURITY_FROM,
            "s.id",
            List.of(
                    QueryField.of("id", "audit.col.id", QueryFieldType.NUMBER, "s.id"),
                    QueryField.of("eventType", "audit.col.event_type", QueryFieldType.TEXT, "s.event_type"),
                    QueryField.of("userName", "audit.col.user", QueryFieldType.TEXT, "u.name")
                            .asNullable().asSearchable(),
                    QueryField.of("userId", "audit.col.user_id", QueryFieldType.NUMBER, "s.user_id")
                            .asNullable().asHidden(),
                    QueryField.of("ip", "audit.col.ip", QueryFieldType.TEXT, "host(s.ip)").asNullable().asSearchable(),
                    QueryField.of("userAgent", "audit.col.user_agent", QueryFieldType.TEXT, "s.user_agent")
                            .asNullable().asSearchable(),
                    QueryField.of("createdAt", "audit.col.created_at", QueryFieldType.INSTANT, "s.created_at")
                            .asSortable()),
            "createdAt",
            true,
            QueryList.DEFAULT_LIMIT,
            QueryList.MAX_LIMIT);

    @Bean
    public QueryList auditLogsQueryList() {
        return LOGS;
    }

    @Bean
    public QueryList auditSecurityEventsQueryList() {
        return SECURITY_EVENTS;
    }
}
