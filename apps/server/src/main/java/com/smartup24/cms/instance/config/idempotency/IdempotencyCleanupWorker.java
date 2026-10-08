package com.smartup24.cms.instance.config.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background worker that cleans up expired idempotency records.
 * Records older than retention-days (14 days by default) are deleted on a schedule.
 */
@Component
@Profile("!migrate")
public class IdempotencyCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupWorker.class);

    private final IdempotencyService idempotencyService;
    private final int retentionDays;

    public IdempotencyCleanupWorker(
            IdempotencyService idempotencyService, @Value("${smc.idempotency.retention-days:14}") int retentionDays) {
        this.idempotencyService = idempotencyService;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${smc.idempotency.cleanup-cron:0 15 2 * * *}", zone = "UTC")
    public void cleanupOldKeys() {
        try {
            int deleted = idempotencyService.cleanupOldKeys(retentionDays);
            if (deleted > 0) {
                log.info("idempotency_keys_deleted count={} olderThanDays={}", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.error("idempotency_cleanup_failed error={}", e.getMessage(), e);
        }
    }
}
