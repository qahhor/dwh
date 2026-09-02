package com.greenwhite.dwh.instance.config.system;

import com.greenwhite.dwh.instance.common.provider.ProviderRegistry;
import com.greenwhite.dwh.instance.config.bootstrap.InstanceBootstrapProperties;
import com.greenwhite.dwh.instance.search.typesense.TypesenseProperties;
import com.greenwhite.dwh.spi.common.ProviderHealth;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SystemInfoService {

    private static final String UNKNOWN = "unknown";
    private static final Duration COMPONENT_TIMEOUT = Duration.ofSeconds(2);

    private final JdbcClient jdbc;
    private final ProviderRegistry providers;
    private final BackupStatusReader backupStatusReader;
    private final TypesenseProperties typesense;
    private final InstanceBootstrapProperties bootstrap;
    private final HttpClient httpClient;
    private final String appVersion;

    public SystemInfoService(
            JdbcClient jdbc,
            ProviderRegistry providers,
            BackupStatusReader backupStatusReader,
            TypesenseProperties typesense,
            InstanceBootstrapProperties bootstrap,
            ObjectProvider<BuildProperties> buildProperties) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.backupStatusReader = backupStatusReader;
        this.typesense = typesense;
        this.bootstrap = bootstrap;
        this.httpClient = HttpClient.newBuilder().connectTimeout(COMPONENT_TIMEOUT).build();
        BuildProperties build = buildProperties.getIfAvailable();
        this.appVersion = build != null && hasText(build.getVersion()) ? build.getVersion() : UNKNOWN;
    }

    public SystemInfoResponse getInfo() {
        Map<String, SystemInfoResponse.Component> components = new LinkedHashMap<>();
        components.put("database", new SystemInfoResponse.Component(databaseStatus()));

        String storageProvider = UNKNOWN;
        try {
            var storage = providers.getActiveStorageProvider();
            storageProvider = storage.getProviderCode();
            components.put("storage", component(storage.checkHealth()));
        } catch (Exception ignored) {
            components.put("storage", new SystemInfoResponse.Component("DOWN"));
        }
        components.put("typesense", new SystemInfoResponse.Component(typesenseStatus()));

        return new SystemInfoResponse(
                appVersion,
                schemaVersion(),
                organization(),
                storageProvider,
                components,
                backupStatusReader.read());
    }

    private String databaseStatus() {
        try {
            jdbc.sql("select 1").query().singleValue();
            return "UP";
        } catch (Exception ignored) {
            return "DOWN";
        }
    }

    private String schemaVersion() {
        try {
            return jdbc.sql("""
                            select version
                            from flyway_schema_history
                            where success
                            order by installed_rank desc
                            limit 1
                            """)
                    .query(String.class)
                    .optional()
                    .filter(SystemInfoService::hasText)
                    .orElse(UNKNOWN);
        } catch (Exception ignored) {
            return UNKNOWN;
        }
    }

    private SystemInfoResponse.Organization organization() {
        try {
            return jdbc.sql("""
                            select client_code as code,
                                   client_name as name,
                                   resource_profile
                            from md_instance_info
                            limit 1
                            """)
                    .query(SystemInfoResponse.Organization.class)
                    .optional()
                    .orElseGet(this::configuredOrganization);
        } catch (Exception ignored) {
            return configuredOrganization();
        }
    }

    private SystemInfoResponse.Organization configuredOrganization() {
        return new SystemInfoResponse.Organization(
                safeValue(bootstrap.clientCode()),
                safeValue(bootstrap.clientName()),
                safeValue(bootstrap.resourceProfile()));
    }

    private String typesenseStatus() {
        if (!typesense.enabled()) {
            return "DISABLED";
        }
        try {
            URI uri = URI.create(typesense.url().replaceAll("/+$", "") + "/health");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(COMPONENT_TIMEOUT)
                    .GET()
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200 ? "UP" : "DEGRADED";
        } catch (Exception ignored) {
            return "DEGRADED";
        }
    }

    private static SystemInfoResponse.Component component(ProviderHealth health) {
        if (health == null) {
            return new SystemInfoResponse.Component("UNKNOWN");
        }
        return new SystemInfoResponse.Component(health.isHealthy() ? "UP" : "DEGRADED");
    }

    private static String safeValue(String value) {
        return hasText(value) ? value : UNKNOWN;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
