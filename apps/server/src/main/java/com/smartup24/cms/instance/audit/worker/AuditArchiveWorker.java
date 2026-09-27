package com.smartup24.cms.instance.audit.worker;

import com.smartup24.cms.instance.audit.archive.AuditArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AuditArchiveWorker(AuditArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @Scheduled(cron = "${smc.audit.archive.cron:0 45 3 * * *}", zone = "UTC")
    public void archive() {
        try {
            archiveService.run();
        } catch (RuntimeException e) {
            // The next night retries: the half-done archive is removed and its partitions wait again.
            log.error("audit_archive_run_failed", e);
        }
    }
}
