package com.smartup24.cms.instance.audit.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** An audit log row as a client sees it: credentials in the old and new row are already masked. */
public record AuditLogView(
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
