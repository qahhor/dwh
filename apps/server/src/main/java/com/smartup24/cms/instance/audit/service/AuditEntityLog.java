package com.smartup24.cms.instance.audit.service;

import com.smartup24.cms.instance.common.entity.runtime.EntityAuditLog;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The audit of the entity runtime (ADR-0032, 6.8): a change of an entity record goes to {@code audit_log} under the
 * entity's audit table, with the request's actor, in the transaction of the change.
 */
@Service
public class AuditEntityLog implements EntityAuditLog {

    private final AuditLogService audit;

    public AuditEntityLog(AuditLogService audit) {
        this.audit = audit;
    }

    @Override
    public void log(
            String table,
            long id,
            String event,
            List<String> changed,
            @Nullable Map<String, Object> before,
            @Nullable Map<String, Object> after) {
        audit.logChange(table, String.valueOf(id), event, changed, before, after);
    }
}
