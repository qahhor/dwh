package com.smartup24.cms.instance.config.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Instance initialization parameters (FR-INST-1). Set by the deployment
 * configuration (environment variables, later Vault). There are no
 * hard-coded values: no demo data and no well-known passwords.
 */
@Validated
@ConfigurationProperties(prefix = "smc.instance")
public record InstanceBootstrapProperties(
        String clientCode,
        String clientName,
        String resourceProfile,
        String adminLogin,
        String adminEmail,
        String adminPassword) {
    public InstanceBootstrapProperties {
        if (resourceProfile == null || resourceProfile.isBlank()) {
            resourceProfile = "S";
        }
    }
}
