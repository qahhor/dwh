package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit entries of {@code ms_tasks} the task's own resources write: a file attached to the task or taken off it.
 * Every change of the task record itself is audited by the general runtime (ADR-0032, 6.8).
 */
@Component
public class MsTaskAuditTrail {

    private static final String TABLE = "ms_tasks";

    private final AuditLogService auditLogService;

    public MsTaskAuditTrail(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** A file attached to the task or taken off it. */
    void fileChanged(Long taskId, UUID fileId, boolean attached) {
        auditLogService.logChange(
                TABLE,
                String.valueOf(taskId),
                "U",
                List.of("files"),
                attached ? null : Map.of("fileId", fileId.toString()),
                attached ? Map.of("fileId", fileId.toString()) : null);
    }
}
