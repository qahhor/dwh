package com.smartup24.cms.instance.kauth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping of expired cookie sessions (FR-AUTH-8, ADR-0034). The active-session condition already refuses a
 * session past its absolute lifetime or idle timeout; this worker only marks such sessions closed, so they leave the
 * open-session indexes and the retention of closed sessions removes them later. A stopped worker never keeps an
 * expired session usable.
 */
@Component
@Profile("!migrate")
public class KauthSessionCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(KauthSessionCleanupWorker.class);

    private final KauthSessionService sessionService;

    public KauthSessionCleanupWorker(KauthSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Scheduled(fixedDelayString = "${smc.session.cleanup-interval:1h}", initialDelayString = "PT1M")
    public void cleanupExpiredSessions() {
        try {
            int closedCount = sessionService.closeExpiredSessions();
            if (closedCount > 0) {
                log.info("Closed {} expired sessions", closedCount);
            }
        } catch (RuntimeException e) {
            log.error("Housekeeping of expired sessions failed: {}", e.getMessage(), e);
        }
    }
}
