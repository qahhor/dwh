package com.smartup24.cms.instance.audit.service;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.api.AuditLogFilter;
import com.smartup24.cms.instance.audit.api.AuditLogView;
import com.smartup24.cms.instance.audit.api.SecurityEventFilter;
import com.smartup24.cms.instance.audit.api.SecurityEventView;
import com.smartup24.cms.instance.audit.repository.AuditListFilters;
import com.smartup24.cms.instance.audit.repository.AuditListFilters.AuditLogFilters;
import com.smartup24.cms.instance.audit.repository.AuditListFilters.SecurityEventFilters;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository.AuditRecord;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository.SecurityEventRecord;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pages of the audit log and the security events through the registry. Every row is redacted before it
 * leaves, as before: credentials in old and new rows and in event details never reach the client or an export.
 */
@Service
public class AuditListService {

    private final QueryListRepository lists;
    private final AuditLogRepository repository;
    private final AuditLogService auditLogService;

    public AuditListService(QueryListRepository lists, AuditLogRepository repository, AuditLogService auditLogService) {
        this.lists = lists;
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    /** @param legacy the old flat filters; they narrow the list and are part of the cursor's fingerprint */
    @Transactional(readOnly = true)
    public KeysetPage<AuditLogView> logs(
            Integer limit, String cursor, String filter, String sort, String search, AuditLogFilter legacy) {
        var filters = new AuditLogFilters(
                legacy.tableName(), legacy.rowPk(), legacy.event(), legacy.userId(), legacy.from(), legacy.to());
        var plan = QueryCompiler.compile(AuditQuery.LOGS, filter, sort, limit, cursor, search, filters.canonical());
        return lists.page(
                plan,
                (rs, row) -> view(auditLogService.redacted(repository.mapAuditRecord(rs, row))),
                AuditListFilters.logPredicate(filters));
    }

    @Transactional(readOnly = true)
    public KeysetPage<SecurityEventView> securityEvents(
            Integer limit, String cursor, String filter, String sort, String search, SecurityEventFilter legacy) {
        var filters =
                new SecurityEventFilters(legacy.eventType(), legacy.userId(), legacy.ip(), legacy.from(), legacy.to());
        var plan = QueryCompiler.compile(
                AuditQuery.SECURITY_EVENTS, filter, sort, limit, cursor, search, filters.canonical());
        return lists.page(
                plan,
                (rs, row) -> view(auditLogService.redacted(repository.mapSecurityEvent(rs, row))),
                AuditListFilters.securityPredicate(filters));
    }

    private static AuditLogView view(AuditRecord r) {
        return new AuditLogView(
                r.id(),
                r.tableName(),
                r.rowPk(),
                r.event(),
                r.changedBy(),
                r.sessionId(),
                r.isApi(),
                r.changedAt(),
                r.changedColumns(),
                r.oldRow(),
                r.newRow(),
                r.changedByName(),
                r.changedByLogin());
    }

    private static SecurityEventView view(SecurityEventRecord r) {
        return new SecurityEventView(
                r.id(),
                r.eventType(),
                r.userId(),
                r.ip(),
                r.userAgent(),
                r.details(),
                r.createdAt(),
                r.userName(),
                r.userLogin());
    }
}
