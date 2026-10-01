package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.audit.api.AuditEntry;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.search.repository.SearchJobRepository;
import com.smartup24.cms.instance.search.repository.SearchJobRepository.JobAuditRow;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Index job operations in the audit log. The audit module writes its own table (ADR-0026); the actor is explicit,
 * the system's own jobs included (null), never a worker's security context.
 */
@Service
public class SearchJobAudit {

    static final String SWITCH = "SWITCH";

    private final SearchJobRepository jobs;
    private final AuditLogService audit;

    public SearchJobAudit(SearchJobRepository jobs, AuditLogService audit) {
        this.jobs = jobs;
        this.audit = audit;
    }

    /** A start, retry or cancellation of a job by the given actor, in the current transaction. */
    public void record(UUID job, @Nullable Long actor, String operation) {
        jobs.auditRow(job, operation).ifPresent(row -> audit.logEntry(entry(job, actor, operation, row)));
    }

    /** The switch of the active generation, by the job's own actor, on the connection of the proof that made it. */
    public void recordSwitch(JdbcClient connection, UUID job) {
        jobs.auditRow(connection, job, SWITCH)
                .ifPresent(row -> audit.logEntry(connection, entry(job, row.actorId(), SWITCH, row)));
    }

    private static AuditEntry entry(UUID job, @Nullable Long actor, String operation, JobAuditRow row) {
        boolean switched = operation.equals(SWITCH);
        return new AuditEntry(
                switched ? "search_index_state" : "search_jobs",
                switched ? "1" : job.toString(),
                "U",
                actor,
                List.of("state"),
                row.newRow());
    }
}
