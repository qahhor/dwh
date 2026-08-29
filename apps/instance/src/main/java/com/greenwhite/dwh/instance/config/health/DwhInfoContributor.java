package com.greenwhite.dwh.instance.config.health;

import com.greenwhite.dwh.instance.search.typesense.TypesenseProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class DwhInfoContributor implements InfoContributor {

    private final JdbcClient jdbcClient;
    private final TypesenseProperties typesenseProperties;
    private final HttpClient httpClient;

    public DwhInfoContributor(JdbcClient jdbcClient, TypesenseProperties typesenseProperties) {
        this.jdbcClient = jdbcClient;
        this.typesenseProperties = typesenseProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> details = new HashMap<>();

        // 1. PostgreSQL DB Check
        try {
            long start = System.currentTimeMillis();
            jdbcClient.sql("SELECT 1").query().singleValue();
            long duration = System.currentTimeMillis() - start;
            details.put("database", Map.of("status", "UP", "responseTimeMs", duration));
        } catch (Exception e) {
            details.put("database", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        // 2. Storage Check
        try {
            File storageDir = new File("/var/lib/dwh/storage");
            if (!storageDir.exists()) {
                storageDir = new File(System.getProperty("java.io.tmpdir"), "dwh-storage");
                storageDir.mkdirs();
            }
            long freeSpaceMb = storageDir.getFreeSpace() / (1024 * 1024);
            long totalSpaceMb = storageDir.getTotalSpace() / (1024 * 1024);
            details.put("storage", Map.of(
                    "status", storageDir.canWrite() ? "UP" : "DEGRADED",
                    "freeMb", freeSpaceMb,
                    "totalMb", totalSpaceMb,
                    "path", storageDir.getAbsolutePath()
            ));
        } catch (Exception e) {
            details.put("storage", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        // 3. Typesense Search Engine Check
        if (typesenseProperties.enabled()) {
            try {
                String healthUrl = typesenseProperties.url() + "/health";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(healthUrl))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    details.put("typesense", Map.of("status", "UP", "url", typesenseProperties.url()));
                } else {
                    details.put("typesense", Map.of("status", "DEGRADED", "httpStatus", response.statusCode()));
                }
            } catch (Exception e) {
                details.put("typesense", Map.of("status", "DEGRADED", "fallback", "PostgreSQL ILIKE Active", "error", e.getMessage()));
            }
        } else {
            details.put("typesense", Map.of("status", "DISABLED", "fallback", "PostgreSQL ILIKE Active"));
        }

        builder.withDetail("dwhPlatform", details);
    }
}
