package com.smartup24.cms.instance.audit.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.CursorUtils;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.metrics.PlatformMetrics;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

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
        if (platformMetrics != null) {
            platformMetrics.incrementAuditMutation();
        }
    }

    @Transactional
    public void logSecurityEvent(
            String eventType, Long userId, String ip, String userAgent, Map<String, Object> details) {
        auditLogRepository.logSecurityEvent(eventType, userId, ip, userAgent, auditDataRedactor.redact(details));
    }

    @Transactional(readOnly = true)
    public KeysetPage<AuditLogRepository.AuditRecord> listAuditLogs(
            String tableName,
            String rowPk,
            String event,
            Long userId,
            Instant from,
            Instant to,
            int limit,
            String cursor) {
        int pageSize = normalizePageSize(limit);
        AuditCursor decodedCursor = decodeCursor(cursor);
        List<AuditLogRepository.AuditRecord> rows = auditLogRepository.listAuditLogs(
                tableName,
                rowPk,
                event,
                userId,
                from,
                to,
                decodedCursor != null ? decodedCursor.timestamp() : null,
                decodedCursor != null ? decodedCursor.id() : null,
                pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<AuditLogRepository.AuditRecord> pageRows = rows.subList(0, Math.min(rows.size(), pageSize));
        long total = decodedCursor == null
                ? auditLogRepository.countAuditLogs(tableName, rowPk, event, userId, from, to)
                : decodedCursor.totalEstimated();
        String nextCursor = hasMore && !pageRows.isEmpty()
                ? encodeCursor(
                        pageRows.getLast().changedAt(), pageRows.getLast().id(), total)
                : null;
        List<AuditLogRepository.AuditRecord> safeRows =
                pageRows.stream().map(this::redacted).toList();
        return KeysetPage.of(safeRows, nextCursor, hasMore, total);
    }

    private final AtomicReference<CachedStats> cachedStats = new AtomicReference<>();

    private record CachedStats(AuditLogRepository.AuditStats stats, Instant expiresAt) {}

    @Transactional(readOnly = true)
    public AuditLogRepository.AuditStats getAuditStats() {
        var now = Instant.now();
        var current = cachedStats.get();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.stats();
        }
        var fresh = auditLogRepository.getAuditStats();
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

    private int normalizePageSize(int requested) {
        return requested <= 0 ? DEFAULT_PAGE_SIZE : Math.min(requested, MAX_PAGE_SIZE);
    }

    private String encodeCursor(Instant timestamp, Long id, long totalEstimated) {
        return timestamp == null || id == null ? null : CursorUtils.encode(timestamp + "|" + id + "|" + totalEstimated);
    }

    private AuditCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        String decoded = CursorUtils.decode(cursor);
        if (decoded == null) throw invalidCursor();
        String[] parts = decoded.split("\\|", -1);
        if (parts.length != 3) throw invalidCursor();
        try {
            Instant timestamp = Instant.parse(parts[0]);
            long id = Long.parseLong(parts[1]);
            long totalEstimated = Long.parseLong(parts[2]);
            if (id <= 0 || totalEstimated < 0) throw invalidCursor();
            return new AuditCursor(timestamp, id, totalEstimated);
        } catch (DateTimeParseException | NumberFormatException ignored) {
            throw invalidCursor();
        }
    }

    private ApiException invalidCursor() {
        return ApiException.badRequest(ErrorCode.BAD_REQUEST, "Некорректный cursor журнала аудита");
    }

    private record AuditCursor(Instant timestamp, Long id, long totalEstimated) {}
}
