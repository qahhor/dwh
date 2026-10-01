package com.smartup24.cms.instance.audit.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A change another module records in the audit log with an explicit actor (ADR-0026): a system operation names the
 * user who asked for it, or null when the system acted on its own, instead of reading the request principal.
 *
 * @param tableName the table whose row changed
 * @param rowPk the key of that row as text
 * @param event {@code I}, {@code U} or {@code D}
 * @param actor the user who asked for the change, or null for the system
 * @param changedColumns the columns the change touched
 * @param newRow the row after the change, as the audit log stores it (null values kept)
 */
public record AuditEntry(
        String tableName,
        String rowPk,
        String event,
        @Nullable Long actor,
        List<String> changedColumns,
        Map<String, Object> newRow) {

    public AuditEntry {
        changedColumns = List.copyOf(changedColumns);
        newRow = Collections.unmodifiableMap(new LinkedHashMap<>(newRow));
    }
}
