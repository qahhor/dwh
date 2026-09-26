package com.greenwhite.dwh.instance.audit.service;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.AuditLogFilters;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.AuditRecord;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.SecurityEventFilters;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.SecurityEventRecord;
import com.greenwhite.dwh.instance.common.query.QueryCompiler;
import com.greenwhite.dwh.instance.common.query.QueryListRepository;
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
    public KeysetPage<AuditRecord> logs(Integer limit, String cursor, String filter, String sort, String search,
                                        AuditLogFilters legacy) {
        var plan = QueryCompiler.compile(AuditQuery.LOGS, filter, sort, limit, cursor, search, legacy.canonical());
        return lists.page(plan, (rs, row) -> auditLogService.redacted(repository.mapAuditRecord(rs, row)),
                AuditLogRepository.logPredicate(legacy));
    }

    @Transactional(readOnly = true)
    public KeysetPage<SecurityEventRecord> securityEvents(Integer limit, String cursor, String filter, String sort,
                                                          String search, SecurityEventFilters legacy) {
        var plan = QueryCompiler.compile(AuditQuery.SECURITY_EVENTS, filter, sort, limit, cursor, search,
                legacy.canonical());
        return lists.page(plan, (rs, row) -> auditLogService.redacted(repository.mapSecurityEvent(rs, row)),
                AuditLogRepository.securityPredicate(legacy));
    }
}
