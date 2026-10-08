package com.smartup24.cms.instance.config.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import tools.jackson.databind.ObjectMapper;

/** Plan 10/10, item 7.3: readiness and backup freshness as the alert rules read them. */
class SystemMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @TempDir
    Path directory;

    @Test
    @DisplayName("7.3: a successful backup exports its age, its state and the accepted age")
    void successfulBackup() throws Exception {
        SimpleMeterRegistry registry =
                bind("{\"status\":\"SUCCESS\",\"completedAt\":\"2026-10-07T10:00:00Z\"}", Duration.ofHours(30));

        assertThat(gauge(registry, "smc.backup.age")).isEqualTo(7200.0);
        assertThat(gauge(registry, "smc.backup.max.age")).isEqualTo(30 * 3600.0);
        assertThat(state(registry, "SUCCESS")).isEqualTo(1.0);
        assertThat(state(registry, "FAILED")).isZero();
        assertThat(state(registry, "NEVER")).isZero();
    }

    @Test
    @DisplayName("7.3: a failed backup has no age; without SMC_BACKUP_MAX_AGE the accepted age is 26 hours")
    void failedBackup() throws Exception {
        SimpleMeterRegistry registry = bind(
                "{\"status\":\"FAILED\",\"completedAt\":\"2026-10-07T10:00:00Z\",\"failureCode\":\"UPLOAD_FAILED\"}",
                Duration.ZERO);

        assertThat(gauge(registry, "smc.backup.age")).isNaN();
        assertThat(gauge(registry, "smc.backup.max.age")).isEqualTo(26 * 3600.0);
        assertThat(state(registry, "FAILED")).isEqualTo(1.0);
        assertThat(state(registry, "SUCCESS")).isZero();
    }

    @Test
    @DisplayName("7.3: no status file is NEVER; readiness without a health endpoint is 0")
    void noBackupAndNoReadiness() throws Exception {
        SimpleMeterRegistry registry = bind(null, Duration.ZERO);

        assertThat(state(registry, "NEVER")).isEqualTo(1.0);
        assertThat(gauge(registry, "smc.health.readiness")).isZero();
    }

    private SimpleMeterRegistry bind(String status, Duration maxAge) throws Exception {
        Path file = directory.resolve("status.json");
        if (status != null) {
            Files.writeString(file, status);
        }
        @SuppressWarnings("unchecked")
        ObjectProvider<HealthEndpoint> health = mock(ObjectProvider.class);
        when(health.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new SystemMetrics(
                        health,
                        new BackupStatusReader(file, new ObjectMapper()),
                        maxAge,
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .bindTo(registry);
        return registry;
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private static double state(SimpleMeterRegistry registry, String status) {
        return registry.get("smc.backup.status").tag("status", status).gauge().value();
    }
}
