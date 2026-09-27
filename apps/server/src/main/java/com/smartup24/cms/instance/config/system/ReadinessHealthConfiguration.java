package com.smartup24.cms.instance.config.system;

import com.smartup24.cms.instance.common.health.ReadinessChecks;
import com.smartup24.cms.instance.mf.scan.ClamAvFileScanner;
import com.smartup24.cms.instance.search.typesense.TypesenseProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Health of the dependencies (plan 10/10, item 0.7), each under a hard deadline.
 *
 * <p>The main database is a member of the readiness group: before, readiness was the application state alone, and
 * with the database stopped the container stayed healthy and kept receiving traffic. Typesense (when enabled),
 * ClamAV (when scanning is required) and pg-dwh (declared next to its data source in {@code FndDwhConfig}) are
 * health components for monitoring only: search falls back to PostgreSQL, uploads fail closed and the DWH module
 * degrades alone, so their outage must not take the whole instance out of traffic. A dependency that is switched
 * off reports UP with the reason.
 */
@Configuration(proxyBeanMethods = false)
public class ReadinessHealthConfiguration {

    private final Duration deadline;

    public ReadinessHealthConfiguration(@Value("${dwh.system.health-timeout:2s}") Duration deadline) {
        this.deadline = deadline.isZero() || deadline.isNegative() ? Duration.ofSeconds(2) : deadline;
    }

    @Bean
    HealthIndicator databaseHealthIndicator(JdbcClient jdbc) {
        return () -> ReadinessChecks.within(deadline, () -> {
            jdbc.sql("select 1").query().singleValue();
            return Health.up().build();
        });
    }

    @Bean
    HealthIndicator typesenseHealthIndicator(ObjectProvider<TypesenseProperties> properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(deadline).build();
        return () -> {
            TypesenseProperties typesense = properties.getIfAvailable();
            if (typesense == null || !typesense.enabled()) {
                return Health.up().withDetail("enabled", false).build();
            }
            return ReadinessChecks.within(deadline, () -> {
                try {
                    URI uri = URI.create(typesense.url().replaceAll("/+$", "") + "/health");
                    int status = client.send(HttpRequest.newBuilder(uri).timeout(deadline).GET().build(),
                            HttpResponse.BodyHandlers.discarding()).statusCode();
                    return status == 200 ? Health.up().build() : Health.down().withDetail("status", status).build();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Health.down().withDetail("reason", "interrupted").build();
                } catch (Exception e) {
                    return Health.down().withDetail("reason", e.getClass().getSimpleName()).build();
                }
            });
        };
    }

    @Bean
    HealthIndicator clamavHealthIndicator(ObjectProvider<ClamAvFileScanner> scanner,
                                          @Value("${dwh.files.scanner.required:false}") boolean required) {
        return () -> {
            if (!required) {
                return Health.up().withDetail("required", false).build();
            }
            ClamAvFileScanner clamav = scanner.getIfAvailable();
            if (clamav == null) {
                return Health.down().withDetail("reason", "scanning is required, but ClamAV is not enabled").build();
            }
            return ReadinessChecks.within(deadline,
                    () -> clamav.ping() ? Health.up().build() : Health.down().withDetail("reason", "no PONG").build());
        };
    }
}
