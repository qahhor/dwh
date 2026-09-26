package com.greenwhite.dwh.instance.audit.service;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.AuditLogFilters;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.SecurityEventFilters;
import com.greenwhite.dwh.instance.common.query.QueryListExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The audit log and the security events as exports (ADR-0018), with the screen's flat filters. Only registry
 * fields are written, so old and new rows and event details are never exported.
 */
@Configuration
public class AuditListExporters {

    @Bean
    public QueryListExporter auditLogsExporter(AuditListService audit) {
        return new QueryListExporter() {
            public String code() { return AuditQuery.LOGS.code(); }

            public Set<String> options() { return Set.of("table_name", "row_pk", "event", "user_id", "from", "to"); }

            public List<FieldErrorItem> checkOptions(Map<String, String> options) {
                List<FieldErrorItem> errors = common(options);
                String event = options.get("event");
                if (event != null && !event.isBlank() && !List.of("I", "U", "D").contains(event)) {
                    errors.add(new FieldErrorItem("event", "EXPORT_INVALID", "I, U or D"));
                }
                return errors;
            }

            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search,
                                      Map<String, String> options) {
                return audit.logs(limit, cursor, filter, sort, search, new AuditLogFilters(options.get("table_name"),
                        options.get("row_pk"), options.get("event"), number(options.get("user_id")),
                        instant(options.get("from")), instant(options.get("to"))));
            }
        };
    }

    @Bean
    public QueryListExporter auditSecurityEventsExporter(AuditListService audit) {
        return new QueryListExporter() {
            public String code() { return AuditQuery.SECURITY_EVENTS.code(); }

            public Set<String> options() { return Set.of("event_type", "user_id", "ip", "from", "to"); }

            public List<FieldErrorItem> checkOptions(Map<String, String> options) {
                return common(options);
            }

            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search,
                                      Map<String, String> options) {
                return audit.securityEvents(limit, cursor, filter, sort, search, new SecurityEventFilters(
                        options.get("event_type"), number(options.get("user_id")), options.get("ip"),
                        instant(options.get("from")), instant(options.get("to"))));
            }
        };
    }

    /** The user id and the time bounds both lists take. */
    private static List<FieldErrorItem> common(Map<String, String> options) {
        List<FieldErrorItem> errors = new ArrayList<>();
        String user = options.get("user_id");
        if (user != null && !user.isBlank() && !user.strip().matches("\\d{1,18}")) {
            errors.add(new FieldErrorItem("user_id", "EXPORT_INVALID", "not a number: " + user));
        }
        for (String key : List.of("from", "to")) {
            String value = options.get(key);
            if (value != null && !value.isBlank()) {
                try {
                    Instant.parse(value.strip());
                } catch (DateTimeParseException e) {
                    errors.add(new FieldErrorItem(key, "EXPORT_INVALID", "not an ISO instant: " + value));
                }
            }
        }
        return errors;
    }

    /** Checked by {@code checkOptions} before the job starts. */
    private static Long number(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value.strip());
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.strip());
    }
}
