package com.greenwhite.dwh.instance.audit.controller;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.audit.pref.AuditPref;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/stats")
    @RequiresPermission(form = AuditPref.FORM_AUDIT_LOG, action = "view")
    public ResponseEntity<AuditLogRepository.AuditStats> getStats() {
        return ResponseEntity.ok(auditLogService.getAuditStats());
    }

    @GetMapping("/logs")
    @RequiresPermission(form = AuditPref.FORM_AUDIT_LOG, action = "view")
    public ResponseEntity<List<AuditLogRepository.AuditRecord>> listLogs(
            @RequestParam(name = "table_name", required = false) String tableName,
            @RequestParam(name = "row_pk", required = false) String rowPk,
            @RequestParam(name = "event", required = false) String event,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "from", required = false) java.time.Instant from,
            @RequestParam(name = "to", required = false) java.time.Instant to,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {

        return ResponseEntity.ok(auditLogService.listAuditLogs(tableName, rowPk, event, userId, from, to, limit));
    }

    @GetMapping("/security-events")
    @RequiresPermission(form = AuditPref.FORM_AUDIT_LOG, action = "view")
    public ResponseEntity<List<AuditLogRepository.SecurityEventRecord>> listSecurityEvents(
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "ip", required = false) String ip,
            @RequestParam(name = "from", required = false) java.time.Instant from,
            @RequestParam(name = "to", required = false) java.time.Instant to,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {

        return ResponseEntity.ok(auditLogService.listSecurityEvents(eventType, userId, ip, from, to, limit));
    }
}

