package com.smartup24.cms.instance.config.system;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Readiness and backup freshness as metrics (plan 10/10, item 7.3; docs/ops/slo.md), so the alert rules see what the
 * System screen and the readiness probe see:
 *
 * <ul>
 *   <li>{@code smc_health_readiness}: 1 while the readiness group is UP, 0 otherwise;
 *   <li>{@code smc_backup_status{status}}: 1 for the state of the last backup attempt (SUCCESS, FAILED, NEVER,
 *       UNKNOWN), 0 for the others;
 *   <li>{@code smc_backup_age_seconds}: the age of the last backup when it succeeded, NaN otherwise;
 *   <li>{@code smc_backup_max_age_seconds}: the accepted age, {@code SMC_BACKUP_MAX_AGE}, or 26 hours (the default
 *       daily interval plus two hours for the run itself) when it is not set.
 * </ul>
 *
 * The backup status file is read at most once per {@link #CACHE} however many gauges a scrape reads.
 */
@Component
public class SystemMetrics implements MeterBinder {

    static final Duration DEFAULT_MAX_AGE = Duration.ofHours(26);
    static final Duration CACHE = Duration.ofSeconds(10);
    static final List<String> BACKUP_STATES = List.of("SUCCESS", "FAILED", "NEVER", "UNKNOWN");

    private static final Logger log = LoggerFactory.getLogger(SystemMetrics.class);

    private final ObjectProvider<HealthEndpoint> health;
    private final BackupStatusReader backups;
    private final BackupFreshnessEvaluator freshness;
    private final Duration maxAge;
    private final Clock clock;
    private volatile BackupStatus cached;
    private volatile Instant cachedAt = Instant.MIN;

    @Autowired
    public SystemMetrics(
            ObjectProvider<HealthEndpoint> health,
            BackupStatusReader backups,
            @Value("${smc.backup.max-age:0s}") Duration configuredMaxAge) {
        this(health, backups, configuredMaxAge, Clock.systemUTC());
    }

    SystemMetrics(
            ObjectProvider<HealthEndpoint> health, BackupStatusReader backups, Duration configuredMaxAge, Clock clock) {
        this.health = health;
        this.backups = backups;
        this.maxAge = configuredMaxAge != null && configuredMaxAge.isPositive() ? configuredMaxAge : DEFAULT_MAX_AGE;
        this.freshness = new BackupFreshnessEvaluator(maxAge, clock);
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("smc.health.readiness", this, SystemMetrics::readiness)
                .description("1 while the readiness group is UP")
                .register(registry);
        for (String state : BACKUP_STATES) {
            Gauge.builder(
                            "smc.backup.status",
                            this,
                            metrics -> state.equals(metrics.backup().status()) ? 1 : 0)
                    .description("1 for the state of the last backup attempt")
                    .tag("status", state)
                    .register(registry);
        }
        Gauge.builder("smc.backup.age", this, SystemMetrics::backupAgeSeconds)
                .description("Age of the last backup when it succeeded")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("smc.backup.max.age", this, metrics -> metrics.maxAge.toSeconds())
                .description("Accepted age of the last successful backup")
                .baseUnit("seconds")
                .register(registry);
    }

    double readiness() {
        try {
            HealthEndpoint endpoint = health.getIfAvailable();
            HealthDescriptor readiness = endpoint == null ? null : endpoint.healthForPath("readiness");
            return readiness != null && Status.UP.equals(readiness.getStatus()) ? 1 : 0;
        } catch (RuntimeException e) {
            log.warn("readiness_metric_failed error={}", e.toString());
            return 0;
        }
    }

    double backupAgeSeconds() {
        Long age = freshness.evaluate(backup()).ageSeconds();
        return age == null ? Double.NaN : age;
    }

    private BackupStatus backup() {
        Instant now = clock.instant();
        BackupStatus status = cached;
        if (status == null || cachedAt.plus(CACHE).isBefore(now)) {
            status = backups.read();
            cached = status;
            cachedAt = now;
        }
        return status;
    }
}
