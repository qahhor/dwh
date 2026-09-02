package com.greenwhite.dwh.instance.kauth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Фоновый воркер очистки неактивных сессий (FR-AUTH-8):
 * Сессии, не проявлявшие активности более 12 часов (last_seen_at < now() - 12h),
 * автоматически переводятся в закрытые (closed_at = now()).
 */
@Component
@Profile("!migrate")
public class KauthSessionCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(KauthSessionCleanupWorker.class);

    private final KauthSessionService sessionService;

    public KauthSessionCleanupWorker(KauthSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Scheduled(fixedDelayString = "${dwh.session.cleanup-interval:1h}",
               initialDelayString = "PT1M")
    public void cleanupInactiveSessions() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofHours(12));
            int closedCount = sessionService.closeInactiveSessions(cutoff);
            if (closedCount > 0) {
                log.info("Автоматически закрыто {} неактивных сессий (> 12 ч без активности)", closedCount);
            }
        } catch (Exception e) {
            log.error("Ошибка при выполнении плановой очистки неактивных сессий: {}", e.getMessage(), e);
        }
    }
}
