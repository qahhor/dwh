package com.greenwhite.dwh.instance.audit.service;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final com.greenwhite.dwh.instance.common.metrics.PlatformMetrics platformMetrics;

    public AuditLogService(AuditLogRepository auditLogRepository,
                           com.greenwhite.dwh.instance.common.metrics.PlatformMetrics platformMetrics) {
        this.auditLogRepository = auditLogRepository;
        this.platformMetrics = platformMetrics;
    }

    @Transactional
    public void logChange(String tableName, String rowPk, String event, List<String> changedColumns,
                          Map<String, Object> oldRow, Map<String, Object> newRow) {

        var principal = SecurityContext.getPrincipal();
        Long userId = principal != null ? principal.userId() : null;
        Long sessionId = principal != null ? principal.sessionId() : null;
        boolean isApi = principal != null && principal.isApi();

        auditLogRepository.logChange(tableName, rowPk, event, userId, sessionId, isApi, changedColumns, oldRow, newRow);
        if (platformMetrics != null) {
            platformMetrics.incrementAuditMutation();
        }
    }


    @Transactional
    public void logSecurityEvent(String eventType, Long userId, String ip, String userAgent, Map<String, Object> details) {
        auditLogRepository.logSecurityEvent(eventType, userId, ip, userAgent, details);
    }

    @Transactional(readOnly = true)
    public List<AuditLogRepository.AuditRecord> listAuditLogs(String tableName, String rowPk, String event, Long userId,
                                                              java.time.Instant from, java.time.Instant to, int limit) {
        return auditLogRepository.listAuditLogs(tableName, rowPk, event, userId, from, to, limit);
    }

    @Transactional(readOnly = true)
    public List<AuditLogRepository.SecurityEventRecord> listSecurityEvents(String eventType, Long userId, String ip,
                                                                          java.time.Instant from, java.time.Instant to, int limit) {
        return auditLogRepository.listSecurityEvents(eventType, userId, ip, from, to, limit);
    }

    @Transactional(readOnly = true)
    public AuditLogRepository.AuditStats getAuditStats() {
        return auditLogRepository.getAuditStats();
    }
}

