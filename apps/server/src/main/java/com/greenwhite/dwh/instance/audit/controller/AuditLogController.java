package com.greenwhite.dwh.instance.audit.controller;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditListService;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.audit.pref.AuditPref;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final AuditListService auditListService;

    public AuditLogController(AuditLogService auditLogService, AuditListService auditListService) {
        this.auditLogService = auditLogService;
        this.auditListService = auditListService;
    }

    @GetMapping("/stats")
    @RequiresPermission(form = AuditPref.FORM_AUDIT_LOG, action = "view")
    public ResponseEntity<AuditLogRepository.AuditStats> getStats() {
        return ResponseEntity.ok(auditLogService.getAuditStats());
    }

    @GetMapping("/logs")
    @RequiresPermission(form = AuditPref.FORM_AUDIT_LOG, action = "view")
    public ResponseEntity<KeysetPage<AuditLogRepository.AuditRecord>> listLogs(
            @RequestParam(name = "table_name", required = false) String tableName,
            @RequestParam(name = "row_pk", required = false) String rowPk,
            @RequestParam(name = "event", required = false) String event,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "from", required = false) java.time.Instant from,
            @RequestParam(name = "to", required = false) java.time.Instant to,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "q", required = false) String query) {

        // Registry list audit.logs (ADR-0016); the flat filters are kept for existing callers.
        return ResponseEntity.ok(auditListService.logs(limit, cursor, filter, sort, query,
                new AuditLogRepository.AuditLogFilters(tableName, rowPk, event, userId, from, to)));
    }

    @GetMapping("/security-events")
    @RequiresPermission(form = AuditPref.FORM_AUDIT_LOG, action = "view")
    public ResponseEntity<KeysetPage<AuditLogRepository.SecurityEventRecord>> listSecurityEvents(
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "ip", required = false) String ip,
            @RequestParam(name = "from", required = false) java.time.Instant from,
            @RequestParam(name = "to", required = false) java.time.Instant to,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "q", required = false) String query) {

        // Registry list audit.security_events (ADR-0016); the flat filters are kept for existing callers.
        return ResponseEntity.ok(auditListService.securityEvents(limit, cursor, filter, sort, query,
                new AuditLogRepository.SecurityEventFilters(eventType, userId, ip, from, to)));
    }
}

