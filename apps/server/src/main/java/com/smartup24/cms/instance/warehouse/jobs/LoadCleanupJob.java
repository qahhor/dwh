package com.smartup24.cms.instance.warehouse.jobs;

import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.warehouse.repository.LoadRepository;
import com.smartup24.cms.instance.warehouse.repository.RawRowRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only place where rows are deleted from {@code raw}: removes the data of loads
 * left in status {@code failed}. Applied loads are never touched, because raw is immutable,
 * and a failed load produces no data. The handler code stays {@code fnd.load_cleanup}: it is a row of the schedule
 * (V105).
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
        List<Long> failed = loads.failedLoadIds();
        if (failed.isEmpty()) {
            return;
        }
        // Deleting across the whole raw layer can take longer than the normal statement limit, hence the maintenance
        // limit
        int removed = raw.deleteRowsOfLoads(failed);
        log.info("load_cleanup loads={} rows_removed={}", failed.size(), removed);
    }
}
