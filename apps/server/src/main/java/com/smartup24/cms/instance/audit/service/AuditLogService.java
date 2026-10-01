package com.smartup24.cms.instance.audit.service;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.api.AuditEntry;
import com.smartup24.cms.instance.audit.api.AuditStatsView;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.common.metrics.PlatformMetrics;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final PlatformMetrics platformMetrics;
    private final AuditDataRedactor auditDataRedactor;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            PlatformMetrics platformMetrics,
            AuditDataRedactor auditDataRedactor) {
        this.auditLogRepository = auditLogRepository;
        this.platformMetrics = platformMetrics;
        this.auditDataRedactor = auditDataRedactor;
    }

    @Transactional
    public void logChange(
            String tableName,
            String rowPk,
            String event,
            List<String> changedColumns,
            Map<String, Object> oldRow,
            Map<String, Object> newRow) {

        var principal = SecurityContext.getPrincipal();
        Long userId = principal != null ? principal.userId() : null;
        Long sessionId = principal != null ? principal.sessionId() : null;
        boolean isApi = principal != null && principal.isApi();

        auditLogRepository.logChange(
                tableName,
                rowPk,
                event,
                userId,
                sessionId,
                isApi,
                changedColumns,
                oldRow == null ? null : auditDataRedactor.redact(oldRow),
                newRow == null ? null : auditDataRedactor.redact(newRow));
        countMutation();
    }

    /**
     * Records a change with an explicit actor (ADR-0026) in the current transaction: a system operation names who
     * asked for it instead of the request principal.
     */
    @Transactional
    public void logEntry(AuditEntry entry) {
        auditLogRepository.logEntry(null, entry, auditDataRedactor.redact(entry.newRow()));
        countMutation();
    }

    /**
     * Records a change with an explicit actor on a connection the caller holds outside the managed transactions, so
     * the entry commits or rolls back with that connection's change.
     */
    public void logEntry(JdbcClient connection, AuditEntry entry) {
        auditLogRepository.logEntry(connection, entry, auditDataRedactor.redact(entry.newRow()));
        countMutation();
    }

    private void countMutation() {
        if (platformMetrics != null) {
            platformMetrics.incrementAuditMutation();
        }
    }

    @Transactional
    public void logSecurityEvent(
            String eventType, Long userId, String ip, String userAgent, Map<String, Object> details) {
        auditLogRepository.logSecurityEvent(eventType, userId, ip, userAgent, auditDataRedactor.redact(details));
    }

    /**
     * A page of the audit rows of one record, newest first (plan 10/10, item 3.5). Nothing is counted: a record's
     * history lives in every audit partition, so the page says only whether more rows follow.
     */
    @Transactional(readOnly = true)
    public KeysetPage<AuditLogRepository.AuditRecord> recordHistory(String tableName, String rowPk, TimePage page) {
        TimePage.Position after = page.after();
        List<AuditLogRepository.AuditRecord> rows = auditLogRepository.listAuditLogs(
                tableName,
                rowPk,
                null,
                null,
                null,
                null,
                after != null ? after.at() : null,
                after != null ? after.id() : null,
                page.limit() + 1);
        return page.page(rows, row -> new TimePage.Position(row.changedAt(), row.id()))
                .map(this::redacted);
    }

    private final AtomicReference<CachedStats> cachedStats = new AtomicReference<>();

    private record CachedStats(AuditStatsView stats, Instant expiresAt) {}

    @Transactional(readOnly = true)
    public AuditStatsView getAuditStats() {
        var now = Instant.now();
        var current = cachedStats.get();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.stats();
        }
        var counted = auditLogRepository.getAuditStats();
        var fresh = new AuditStatsView(
                counted.totalAuditLogs(),
                counted.totalSecurityEvents(),
                counted.securityEventsLast24h(),
                counted.failedLoginsLast24h(),
                counted.computedAt());
        cachedStats.set(new CachedStats(fresh, now.plusSeconds(15)));
        return fresh;
    }

    /** The row as a client may see it: credentials in the old and new row masked. */
    public AuditLogRepository.AuditRecord redacted(AuditLogRepository.AuditRecord record) {
        return new AuditLogRepository.AuditRecord(
                record.id(),
                record.tableName(),
                record.rowPk(),
                record.event(),
                record.changedBy(),
                record.sessionId(),
                record.isApi(),
                record.changedAt(),
                record.changedColumns(),
                auditDataRedactor.redact(record.oldRow()),
                auditDataRedactor.redact(record.newRow()),
                record.changedByName(),
                record.changedByLogin());
    }

    /** The event as a client may see it: credentials in its details masked. */
    public AuditLogRepository.SecurityEventRecord redacted(AuditLogRepository.SecurityEventRecord record) {
        return new AuditLogRepository.SecurityEventRecord(
                record.id(),
                record.eventType(),
                record.userId(),
                record.ip(),
                record.userAgent(),
                auditDataRedactor.redact(record.details()),
                record.createdAt(),
                record.userName(),
                record.userLogin());
    }
}
