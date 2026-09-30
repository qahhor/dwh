package com.smartup24.cms.instance.search.typesense;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Health and installation metadata of the Typesense dependency, as shown by search status and the preflight. */
@Component
public class TypesenseHealth {

    // The category stays the transport's so existing log routing keeps matching.
    private static final Logger log = LoggerFactory.getLogger(TypesenseClient.class);

    private final TypesenseClient client;

    public TypesenseHealth(TypesenseClient client) {
        this.client = client;
    }

    public boolean isHealthy() {
        if (!client.isEnabled()) return false;
        try {
            var response = client.metadata("/health");
            return response.path("ok").isBoolean() && response.path("ok").asBoolean();
        } catch (Exception e) {
            log.warn("typesense_health_unavailable error={}", e.toString());
            return false;
        }
    }

    public DependencyMetadata observeDependency() {
        if (!client.isEnabled()) return new DependencyMetadata(false, false, null, null, null, "DISABLED");
        if (!isHealthy()) return new DependencyMetadata(true, false, null, null, null, "DEPENDENCY_UNAVAILABLE");
        String version = null;
        Long used = null;
        Long total = null;
        String error = null;
        try {
            var value = client.metadata("/debug").path("version");
            if (value.isString()
                    && value.asString().length() <= 48
                    && value.asString().matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-+][A-Za-z0-9.-]+)?"))
                version = value.asString();
            else error = "METADATA_UNAVAILABLE";
        } catch (RuntimeException unavailable) {
            log.warn("typesense_metadata_unavailable error={}", unavailable.toString());
            error = "METADATA_UNAVAILABLE";
        }
        try {
            var metrics = client.metadata("/metrics.json");
            used = TypesenseClient.nonnegativeInteger(metrics.path("system_disk_used_bytes"), true);
            total = TypesenseClient.nonnegativeInteger(metrics.path("system_disk_total_bytes"), true);
            if (used == null || total == null) error = "METADATA_UNAVAILABLE";
        } catch (RuntimeException unavailable) {
            log.warn("typesense_metadata_unavailable error={}", unavailable.toString());
            error = "METADATA_UNAVAILABLE";
        }
        return new DependencyMetadata(true, true, version, used, total, error);
    }

    public TransportBudgets transportBudgets() {
        return new TransportBudgets(TypesenseClient.CONNECT_TIMEOUT_MS, TypesenseClient.READ_TIMEOUT_MS);
    }

    public record DependencyMetadata(
            boolean enabled,
            boolean healthy,
            String version,
            Long installationDiskUsedBytes,
            Long installationDiskTotalBytes,
            String errorCode) {}

    public record TransportBudgets(int connectTimeoutMs, int readTimeoutMs) {}
}
