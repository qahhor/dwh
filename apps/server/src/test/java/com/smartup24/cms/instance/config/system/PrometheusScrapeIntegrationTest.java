package com.smartup24.cms.instance.config.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.metrics.OutboxMetrics;
import com.smartup24.cms.instance.common.metrics.TaskRunMetrics;
import com.smartup24.cms.instance.jobs.runner.JobMetrics;
import com.smartup24.cms.instance.search.service.SearchMetrics;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Plan 10/10, item 7.3: the scrape endpoint exposes the latency histogram with the SLO buckets, and every metric the
 * Prometheus rules and the Grafana dashboards in {@code deploy/observability} read is one the server really exports.
 */
class PrometheusScrapeIntegrationTest extends EmbeddedPostgresTest {

    private static final Path OBSERVABILITY = Path.of("../../deploy/observability");
    /** Metric names the rules and dashboards read; recording rules (with a colon) and {@code up} are Prometheus'. */
    private static final Pattern METRIC = Pattern.compile(
            "(?<![:\\w])((?:smc|http_server_requests|hikaricp|disk|jvm|process|system|executor|tomcat|jdbc)_[a-z0-9_]+)\\b");

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JobMetrics jobMetrics;

    @Autowired
    SearchMetrics searchMetrics;

    @Test
    @DisplayName("7.3: http.server.requests is a histogram with the 100 ms, 300 ms and 1 s SLO buckets")
    void httpServerRequestsHistogramHasSloBuckets() throws Exception {
        get(serverPort, "/api/v1/i18n/languages");

        String scrape = get(managementPort, "/actuator/prometheus");

        Set<String> buckets = scrape.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_bucket{"))
                .map(line -> {
                    Matcher le = Pattern.compile("le=\"([^\"]+)\"").matcher(line);
                    return le.find() ? le.group(1) : "";
                })
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(buckets).contains("0.1", "0.3", "1.0", "+Inf");
        // A percentiles histogram, not the SLO buckets alone: the quantiles of the dashboards need the fine buckets.
        assertThat(buckets).hasSizeGreaterThan(20);
        assertThat(scrape).contains("application=\"smartupcms\"");
        // The readiness group is UP once the context serves requests (the main database answers).
        assertThat(scrape).containsPattern("(?m)^smc_health_readiness\\{[^}]*\\} 1\\.0$");
    }

    @Test
    @DisplayName("7.3: every metric of the alert rules and dashboards is exported by the server")
    void rulesAndDashboardsReadExportedMetrics() throws Exception {
        get(serverPort, "/api/v1/i18n/languages");
        // Timers and counters appear with their first record: make the components record once, as they do at work.
        jobMetrics.executed("test.metrics", 1_000_000, true);
        jobMetrics.sample();
        OutboxMetrics.of(registry, "webhook").failed(1_000_000, true);
        TaskRunMetrics.record(registry, "retention", System.nanoTime(), true);
        searchMetrics.query("ALL", "POSTGRES", false, true, 1_000_000);
        searchMetrics.queue(true, 0, 0);

        String scrape = get(managementPort, "/actuator/prometheus");
        Set<String> exported = scrape.lines()
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .map(line -> line.split("[{ ]", 2)[0])
                .collect(Collectors.toSet());

        Set<String> referenced = referencedMetrics();
        assertThat(referenced)
                .as("the rules read the server's metrics")
                .contains(
                        "http_server_requests_seconds_bucket",
                        "smc_jobs_queue_lag_seconds",
                        "smc_outbox_lag_seconds",
                        "smc_backup_age_seconds",
                        "smc_health_readiness",
                        "hikaricp_connections_active");
        assertThat(exported).as("metrics read by deploy/observability").containsAll(referenced);
    }

    private static Set<String> referencedMetrics() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(OBSERVABILITY)) {
            for (Path file : files.filter(path ->
                            path.toString().endsWith(".yml") || path.toString().endsWith(".json"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/tests/"))
                    .toList()) {
                Matcher metric = METRIC.matcher(read(file));
                while (metric.find()) {
                    names.add(metric.group(1));
                }
            }
        }
        return names;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String get(int port, String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            return response.body();
        }
    }
}
