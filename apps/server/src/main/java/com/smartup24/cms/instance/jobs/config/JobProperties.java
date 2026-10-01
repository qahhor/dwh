package com.smartup24.cms.instance.jobs.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retry and lease of the job queue (plan 10/10, item 3.8); documented in docs/ops/operations-runbook.md.
 *
 * @param maxAttempts     how many times a job runs before it is marked failed; 1 — never retried
 * @param retryBackoff    the pause after the first failed attempt; each next failure doubles it
 * @param retryBackoffMax the longest pause between attempts
 * @param lease           how long a claim holds a job without renewal; the runner renews it every third of this while
 *                        the handler works, so only a dead node lets it run out and another node take the job again
 */
@Validated
@ConfigurationProperties(prefix = "smc.jobs")
public record JobProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30s") Duration retryBackoff,
        @DefaultValue("1h") Duration retryBackoffMax,
        @DefaultValue("5m") Duration lease) {

    public JobProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("smc.jobs.max-attempts must be at least 1");
        }
        if (retryBackoff == null || retryBackoff.isNegative()) {
            throw new IllegalArgumentException("smc.jobs.retry-backoff must not be negative");
        }
        if (retryBackoffMax == null || retryBackoffMax.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException("smc.jobs.retry-backoff-max must not be shorter than retry-backoff");
        }
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("smc.jobs.lease must be positive");
        }
    }

    /** The values of application.yml, for runners built by hand. */
    public static JobProperties defaults() {
        return new JobProperties(5, Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMinutes(5));
    }

    /** The pause after the given failed attempt (1-based): the backoff doubled per earlier failure, capped. */
    public Duration backoffAfter(int attempt) {
        Duration pause = retryBackoff;
        for (int i = 1; i < attempt && pause.compareTo(retryBackoffMax) < 0; i++) {
            pause = pause.multipliedBy(2);
        }
        return pause.compareTo(retryBackoffMax) > 0 ? retryBackoffMax : pause;
    }
}
