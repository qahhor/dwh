package com.smartup24.cms.instance.warehouse.jobs;

import com.smartup24.cms.instance.common.actor.AuditActorContext;
import com.smartup24.cms.instance.common.security.SecurityEventLog;
import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.warehouse.repository.LoadRepository;
import com.smartup24.cms.instance.warehouse.repository.RawRowRepository;
import com.smartup24.cms.instance.warehouse.repository.SourceFileRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cross-database check: there are no foreign keys between OLTP and pg-dwh, so a job looks for orphans instead.
 * A {@code raw} row that refers to a load or a framework file that does not exist becomes an {@code xdb_mismatch}
 * event in {@code security_events}, written through the platform contract audit implements; the data itself is left
 * untouched. The handler code stays {@code fnd.xdb_check}: it is a row of the schedule (V105).
 */
@Component
public class CrossDatabaseCheckJob implements JobHandler {

    public static final String CODE = "fnd.xdb_check";
    public static final String EVENT = "xdb_mismatch";
    private static final Logger log = LoggerFactory.getLogger(CrossDatabaseCheckJob.class);
    /** The check runs on the server itself: it has no client address, yet the ip column is mandatory. */
    private static final String LOCAL_IP = "127.0.0.1";

    private final RawRowRepository raw;
    private final LoadRepository loads;
    private final SourceFileRepository files;
    private final AuditActorContext actors;
    private final SecurityEventLog audit;

    public CrossDatabaseCheckJob(
            RawRowRepository raw,
            LoadRepository loads,
            SourceFileRepository files,
            AuditActorContext actors,
            SecurityEventLog audit) {
        this.raw = raw;
        this.loads = loads;
        this.files = files;
        this.actors = actors;
        this.audit = audit;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void run(Map<String, Object> args) {
        // Both passes read the whole of raw, so they run under the maintenance limit, not the usual query limit
        RawRowRepository.References references = raw.references();
        List<Long> loadIds = references.loadIds();
        List<UUID> fileIds = references.fileIds();

        long systemUserId = actors.system().userId();
        int mismatches = 0;
        for (Long orphan : orphans(loadIds, loads.existingIds(loadIds))) {
            report(systemUserId, Map.of("load_id", orphan));
            mismatches++;
        }
        for (UUID orphan : orphans(fileIds, files.existingIds(fileIds))) {
            report(systemUserId, Map.of("source_file_id", orphan.toString()));
            mismatches++;
        }
        log.info("xdb_check loads={} files={} mismatches={}", loadIds.size(), fileIds.size(), mismatches);
    }

    private static <T> List<T> orphans(List<T> all, Set<T> known) {
        return all.stream().filter(id -> !known.contains(id)).toList();
    }

    private void report(long systemUserId, Map<String, Object> details) {
        audit.logSecurityEvent(EVENT, systemUserId, LOCAL_IP, CODE, details);
    }
}
