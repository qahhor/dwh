package com.smartup24.cms.instance.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of trusted proxy servers and networks (FR-SEC-2).
 * <p>
 * Includes by default:
 * <ul>
 *   <li>127.0.0.1/32, ::1/128 (loopback)</li>
 *   <li>10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 (private IPv4 networks, including Docker Bridge/Compose)</li>
 *   <li>fc00::/7, fe80::/10 (unique local and link-local IPv6 networks)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "dwh.security")
public record TrustedProxyProperties(List<String> trustedProxies) {
    public static final List<String> DEFAULT_TRUSTED_PROXIES = List.of(
            "127.0.0.1/32", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7", "fe80::/10");

    public TrustedProxyProperties {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            trustedProxies = DEFAULT_TRUSTED_PROXIES;
        }
    }
}
