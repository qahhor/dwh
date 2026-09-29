package com.smartup24.cms.instance.audit.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.api.AuditLogFilter;
import com.smartup24.cms.instance.audit.api.AuditLogView;
import com.smartup24.cms.instance.audit.api.AuditStatsView;
import com.smartup24.cms.instance.audit.api.SecurityEventFilter;
import com.smartup24.cms.instance.audit.api.SecurityEventView;
import com.smartup24.cms.instance.audit.pref.AuditPref;
import com.smartup24.cms.instance.audit.service.AuditListService;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import java.time.Instant;
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
    public ResponseEntity<AuditStatsView> getStats() {
        return ResponseEntity.ok(auditLogService.getAuditStats());
    }

    @GetMapping("/logs")
    @RequiresPermission(form = AuditPref.FORM_AUDIT_LOG, action = "view")
    public ResponseEntity<KeysetPage<AuditLogView>> listLogs(
            @RequestParam(name = "tableName", required = false) String tableName,
            @RequestParam(name = "rowPk", required = false) String rowPk,
            @RequestParam(name = "event", required = false) String event,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "q", required = false) String query) {

        // Registry list audit.logs (ADR-0016); the flat filters are kept for existing callers.
        return ResponseEntity.ok(auditListService.logs(
                limit, cursor, filter, sort, query, new AuditLogFilter(tableName, rowPk, event, userId, from, to)));
    }

    @GetMapping("/security-events")
    @RequiresPermission(form = AuditPref.FORM_AUDIT_LOG, action = "view")
    public ResponseEntity<KeysetPage<SecurityEventView>> listSecurityEvents(
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "ip", required = false) String ip,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "q", required = false) String query) {

        // Registry list audit.security_events (ADR-0016); the flat filters are kept for existing callers.
        return ResponseEntity.ok(auditListService.securityEvents(
                limit, cursor, filter, sort, query, new SecurityEventFilter(eventType, userId, ip, from, to)));
    }
}
