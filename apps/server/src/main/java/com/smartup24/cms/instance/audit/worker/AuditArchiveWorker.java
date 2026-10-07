package com.smartup24.cms.instance.audit.worker;

import com.smartup24.cms.instance.audit.archive.AuditArchiveService;
import com.smartup24.cms.instance.common.metrics.TaskRunMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the audit archive every night, after the partition maintenance (decision of 2026-09-27). A partition closes
 * at midnight UTC, so a daily run sees every size and week threshold on the day it is crossed.
 */
@Component
@Profile("!migrate")
public class AuditArchiveWorker {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveWorker.class);

    private final AuditArchiveService archiveService;
    private final ObjectProvider<MeterRegistry> meters;

    public AuditArchiveWorker(AuditArchiveService archiveService, ObjectProvider<MeterRegistry> meters) {
        this.archiveService = archiveService;
        this.meters = meters;
    }

    @Scheduled(cron = "${smc.audit.archive.cron:0 45 3 * * *}", zone = "UTC")
    public void archive() {
        long started = System.nanoTime();
        boolean success = false;
        try {
            archiveService.run();
            success = true;
        } catch (RuntimeException e) {
            // The next night retries: the half-done archive is removed and its partitions wait again.
            log.error("audit_archive_run_failed", e);
        } finally {
            // Plan 10/10, item 7.3: smc_task_run_seconds{task="audit_archive"}; a failed run raises an alert.
            TaskRunMetrics.record(meters.getIfAvailable(), "audit_archive", started, success);
        }
    }
}
