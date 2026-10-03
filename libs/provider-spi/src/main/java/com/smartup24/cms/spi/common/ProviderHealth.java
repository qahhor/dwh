package com.smartup24.cms.spi.common;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.time.Instant;

/**
 * Health status descriptor for infrastructure providers according to FR-PLUG-4.
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record ProviderHealth(
        String providerName, boolean isHealthy, String message, long latencyMs, Instant checkedAt) {
    public static ProviderHealth healthy(String providerName, long latencyMs) {
        return new ProviderHealth(providerName, true, "OK", latencyMs, Instant.now());
    }

    public static ProviderHealth unhealthy(String providerName, String message, long latencyMs) {
        return new ProviderHealth(providerName, false, message, latencyMs, Instant.now());
    }
}
