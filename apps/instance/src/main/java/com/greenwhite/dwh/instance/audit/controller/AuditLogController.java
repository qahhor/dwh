package com.greenwhite.dwh.instance.audit.controller;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
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

    @GetMapping("/logs")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<List<AuditLogRepository.AuditRecord>> listLogs(
            @RequestParam(name = "table_name", required = false) String tableName,
            @RequestParam(name = "row_pk", required = false) String rowPk,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {

        return ResponseEntity.ok(auditLogService.listAuditLogs(tableName, rowPk, userId, limit));
    }

    @GetMapping("/security-events")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<List<AuditLogRepository.SecurityEventRecord>> listSecurityEvents(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {

        return ResponseEntity.ok(auditLogService.listSecurityEvents(limit));
    }
}
