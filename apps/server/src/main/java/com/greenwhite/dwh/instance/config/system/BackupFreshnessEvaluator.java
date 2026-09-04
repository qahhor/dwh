package com.greenwhite.dwh.instance.config.system;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

final class BackupFreshnessEvaluator {

    private final Duration maxAge;
    private final Clock clock;

    BackupFreshnessEvaluator(Duration maxAge) {
        this(maxAge, Clock.systemUTC());
    }

    BackupFreshnessEvaluator(Duration maxAge, Clock clock) {
        this.maxAge = maxAge != null && maxAge.isPositive() ? maxAge : null;
        this.clock = clock;
    }

    BackupStatus evaluate(BackupStatus status) {
        Long maxAgeSeconds = maxAge == null ? null : maxAge.toSeconds();
        if (!"SUCCESS".equals(status.status()) || status.completedAt() == null) {
            return withFreshness(status, "NOT_APPLICABLE", null, maxAgeSeconds);
        }

        Instant now = clock.instant();
        if (status.completedAt().isAfter(now)) {
            return withFreshness(status, "UNKNOWN", null, maxAgeSeconds);
        }

        long ageSeconds = Duration.between(status.completedAt(), now).toSeconds();
        if (maxAge == null) {
            return withFreshness(status, "NOT_CONFIGURED", ageSeconds, null);
        }
        String freshness = ageSeconds <= maxAgeSeconds ? "CURRENT" : "STALE";
        return withFreshness(status, freshness, ageSeconds, maxAgeSeconds);
    }

    private static BackupStatus withFreshness(
            BackupStatus status,
            String freshness,
            Long ageSeconds,
            Long maxAgeSeconds) {
        return new BackupStatus(
                status.status(),
                status.completedAt(),
                status.failureCode(),
                freshness,
                ageSeconds,
                maxAgeSeconds);
    }
}
