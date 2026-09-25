package com.greenwhite.dwh.instance.common.history;

import java.util.Map;
import java.util.Set;

/**
 * A kind of record whose change history a card may show (ADR-0017). A module
 * registers one bean per kind: the audit module reads the history but must not
 * depend on the modules that write it, so each module says here which audit
 * table holds its records, which right opens the history and whether the
 * viewer may see a given record at all (its data scope).
 */
public interface RecordHistorySource {

    /** The name in the URL, e.g. {@code tasks} in {@code /api/v1/history/tasks/42}. */
    String key();

    /** The {@code audit_log.table_name} the module writes this record's changes under. */
    String tableName();

    /** The right that opens this history; the same right that opens the record. */
    String form();

    String action();

    /**
     * Throws a not-found {@code ApiException} when the record does not exist or
     * lies outside the viewer's data scope, so the history of a record the viewer
     * cannot open is never shown, and its existence is not revealed either.
     */
    void requireVisible(String recordId);

    /**
     * Label keys for the fields a person understands, by field name in camelCase
     * (audit rows mix {@code project_id} and {@code projectId}; both map here as
     * {@code projectId}). A field without a label is shown by its name.
     */
    default Map<String, String> fieldLabels() {
        return Map.of();
    }

    /** Fields never shown in the history, in camelCase, beyond the technical ones. */
    default Set<String> hiddenFields() {
        return Set.of();
    }
}
