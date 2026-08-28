package com.greenwhite.dwh.instance.config.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры инициализации экземпляра (FR-INST-1). Задаются конфигурацией
 * развёртывания (env / Vault в фазе P). Захардкоженных значений нет —
 * AUDIT-03 C-1/C-2.
 */
@ConfigurationProperties(prefix = "dwh.instance")
public record InstanceBootstrapProperties(
        String clientCode,
        String clientName,
        String resourceProfile,
        String adminLogin,
        String adminEmail,
        String adminPassword
) {
    public InstanceBootstrapProperties {
        if (resourceProfile == null || resourceProfile.isBlank()) {
            resourceProfile = "S";
        }
    }
}
