package com.smartup24.cms.instance.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of trusted proxy servers and networks (FR-SEC-2, ADR-0034).
 * <p>
 * Only a direct peer from this list may set {@code X-Forwarded-For} / {@code X-Forwarded-Proto}. The default is
 * loopback only: an installation names its proxies explicitly (Compose passes the network of its web container,
 * {@code SMC_SECURITY_TRUSTED_PROXIES}). Private networks are not trusted wholesale.
 */
@Validated
@ConfigurationProperties(prefix = "smc.security")
public record TrustedProxyProperties(List<String> trustedProxies) {
    public static final List<String> DEFAULT_TRUSTED_PROXIES = List.of("127.0.0.1/32", "::1/128");

    public TrustedProxyProperties {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            trustedProxies = DEFAULT_TRUSTED_PROXIES;
        }
    }
}
