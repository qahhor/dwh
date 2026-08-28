package com.greenwhite.dwh.spi.common;

import java.time.Instant;

/**
 * Health status descriptor for infrastructure providers according to FR-PLUG-4.
 */
public record ProviderHealth(
        String providerName,
        boolean isHealthy,
        String message,
        long latencyMs,
        Instant checkedAt
) {
    public static ProviderHealth healthy(String providerName, long latencyMs) {
        return new ProviderHealth(providerName, true, "OK", latencyMs, Instant.now());
    }

    public static ProviderHealth unhealthy(String providerName, String message, long latencyMs) {
        return new ProviderHealth(providerName, false, message, latencyMs, Instant.now());
    }
}
