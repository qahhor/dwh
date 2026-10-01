package com.smartup24.cms.instance.common.entity.runtime;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The audit log as the runtime writes it (ADR-0032, 6.8, step 11 of 6.3): a change of an entity record is written by
 * the platform, never by the module. The audit module implements it, so {@code common} depends on no module; the row
 * joins the transaction of the change.
 */
public interface EntityAuditLog {

    /**
     * Writes one change of a record.
     *
     * @param table   the entity's {@code audit_log.table_name}
     * @param id      the record
     * @param event   {@code I}, {@code U} or {@code D}
     * @param changed the keys of the changed fields
     * @param before  the record's audited values before, or null for a create
     * @param after   the record's audited values after, or null for a delete
     */
    void log(
            String table,
            long id,
            String event,
            List<String> changed,
            @Nullable Map<String, Object> before,
            @Nullable Map<String, Object> after);
}
