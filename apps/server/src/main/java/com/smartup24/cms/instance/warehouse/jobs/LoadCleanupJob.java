package com.smartup24.cms.instance.warehouse.jobs;

import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.warehouse.repository.LoadRepository;
import com.smartup24.cms.instance.warehouse.repository.RawRowRepository;
import com.smartup24.cms.instance.warehouse.repository.RawRowRepository.RawPartition;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only place where rows leave {@code raw}: drops the partitions of loads left in status {@code failed} (plan
 * 10/10, item 7.8). Applied and superseded loads are never touched, because raw is immutable, and a failed load
 * produces no data. A partition is detached concurrently and dropped, never emptied with {@code DELETE}: the cleanup of
 * a million-row load takes well under a second and leaves no dead tuples. Partitions of loads the OLTP ledger does not
 * know are left for the cross-database check to report. The handler code stays {@code fnd.load_cleanup}: it is a row
 * of the schedule (V105).
 */
@Component
public class LoadCleanupJob implements JobHandler {

    public static final String CODE = "fnd.load_cleanup";
    private static final Logger log = LoggerFactory.getLogger(LoadCleanupJob.class);

    private final LoadRepository loads;
    private final RawRowRepository raw;

    public LoadCleanupJob(LoadRepository loads, RawRowRepository raw) {
        this.loads = loads;
        this.raw = raw;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void run(Map<String, Object> args) {
        List<RawPartition> partitions = raw.partitions();
        if (partitions.isEmpty()) {
            return;
        }
        Set<Long> failed = new HashSet<>(loads.failedLoadIds());
        RuntimeException firstFailure = null;
        int dropped = 0;
        for (RawPartition partition : partitions) {
            if (!failed.contains(partition.loadId())) {
                continue;
            }
            try {
                raw.drop(partition);
                dropped++;
            } catch (RuntimeException failure) {
                // One partition that cannot go now does not keep the others; the run still fails and is retried
                log.error("load_cleanup_failed load_id={}", partition.loadId(), failure);
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
        }
        log.info("load_cleanup partitions_dropped={}", dropped);
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
