package com.smartup24.cms.instance.config.system;

import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.config.bootstrap.InstanceBootstrapProperties;
import com.smartup24.cms.instance.search.typesense.TypesenseProperties;
import com.smartup24.cms.spi.common.ProviderHealth;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SystemInfoService {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoService.class);

    private static final String UNKNOWN = "unknown";
    private static final Duration COMPONENT_TIMEOUT = Duration.ofSeconds(2);

    private final JdbcClient jdbc;
    private final ProviderRegistry providers;
    private final BackupStatusReader backupStatusReader;
    private final TypesenseProperties typesense;
    private final InstanceBootstrapProperties bootstrap;
    private final HttpClient httpClient;
    private final String appVersion;
    private final Duration healthTimeout;
    private final BackupFreshnessEvaluator backupFreshnessEvaluator;
    private final ExecutorService healthExecutor;

    public SystemInfoService(
            JdbcClient jdbc,
            ProviderRegistry providers,
            BackupStatusReader backupStatusReader,
            TypesenseProperties typesense,
            InstanceBootstrapProperties bootstrap,
            ObjectProvider<BuildProperties> buildProperties,
            @Value("${smc.system.health-timeout:2s}") Duration healthTimeout,
            @Value("${smc.backup.max-age:0s}") Duration backupMaxAge) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.backupStatusReader = backupStatusReader;
        this.typesense = typesense;
        this.bootstrap = bootstrap;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(COMPONENT_TIMEOUT).build();
        BuildProperties build = buildProperties.getIfAvailable();
        this.appVersion = build != null && hasText(build.getVersion()) ? build.getVersion() : UNKNOWN;
        this.healthTimeout = healthTimeout == null || healthTimeout.isZero() || healthTimeout.isNegative()
                ? COMPONENT_TIMEOUT
                : healthTimeout;
        this.backupFreshnessEvaluator = new BackupFreshnessEvaluator(backupMaxAge);
        this.healthExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public SystemInfoResponse getInfo() {
        CompletableFuture<SystemInfoResponse.Component> database = probe(
                () -> new SystemInfoResponse.Component(databaseStatus()), new SystemInfoResponse.Component("DOWN"));
        CompletableFuture<SystemInfoResponse.Component> typesenseHealth = probe(
                () -> new SystemInfoResponse.Component(typesenseStatus()),
                new SystemInfoResponse.Component("DEGRADED"));

        String storageProvider = UNKNOWN;
        CompletableFuture<SystemInfoResponse.Component> storageHealth;
        try {
            var storage = providers.getActiveStorageProvider();
            storageProvider = storage.getProviderCode();
            storageHealth = probe(() -> component(storage.checkHealth()), new SystemInfoResponse.Component("DEGRADED"));
        } catch (Exception storageUnavailable) {
            log.warn("system_info_storage_unavailable error={}", storageUnavailable.toString());
            storageHealth = CompletableFuture.completedFuture(new SystemInfoResponse.Component("DOWN"));
        }

        SystemInfoResponse.Organization organizationFallback = configuredOrganization();
        CompletableFuture<String> schemaVersion = probe(this::schemaVersion, UNKNOWN);
        CompletableFuture<SystemInfoResponse.Organization> organization =
                probe(this::organization, organizationFallback);
        BackupStatus unavailableBackup =
                backupFreshnessEvaluator.evaluate(new BackupStatus("UNKNOWN", null, "STATUS_UNAVAILABLE"));
        CompletableFuture<BackupStatus> backup =
                probe(() -> backupFreshnessEvaluator.evaluate(backupStatusReader.read()), unavailableBackup);

        Map<String, SystemInfoResponse.Component> components = new LinkedHashMap<>();
        components.put("database", database.join());
        components.put("storage", storageHealth.join());
        components.put("typesense", typesenseHealth.join());

        return new SystemInfoResponse(
                appVersion,
                schemaVersion.join(),
                organization.join(),
                storageProvider,
                components,
                backup.join(),
                Instant.now());
    }

    @PreDestroy
    void close() {
        healthExecutor.shutdownNow();
    }

    private <T> CompletableFuture<T> probe(Supplier<T> supplier, T fallback) {
        return CompletableFuture.supplyAsync(supplier, healthExecutor)
                .completeOnTimeout(fallback, healthTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(ignored -> fallback);
    }

    private String databaseStatus() {
        try {
            jdbc.sql("select 1").query().singleValue();
            return "UP";
        } catch (Exception unavailable) {
            log.warn("system_info_database_down error={}", unavailable.toString());
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
        } catch (Exception unavailable) {
            log.warn("system_info_schema_version_unavailable error={}", unavailable.toString());
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
        } catch (Exception unavailable) {
            log.warn("system_info_organization_unavailable error={}", unavailable.toString());
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
            HttpRequest request =
                    HttpRequest.newBuilder(uri).timeout(COMPONENT_TIMEOUT).GET().build();
            int status = httpClient
                    .send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode();
            return status == 200 ? "UP" : "DEGRADED";
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("system_info_typesense_unavailable error={}", unavailable.toString());
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
