package com.smartup24.cms.instance.audit.api;

import java.time.Instant;

/** The flat filters of the audit log list, kept for existing callers next to the registry filter. */
public record AuditLogFilter(String tableName, String rowPk, String event, Long userId, Instant from, Instant to) {

    public static AuditLogFilter none() {
        return new AuditLogFilter(null, null, null, null, null, null);
    }
}
