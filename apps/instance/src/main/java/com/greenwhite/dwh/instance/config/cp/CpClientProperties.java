package com.greenwhite.dwh.instance.config.cp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Параметры связи экземпляра с control plane (FR-INST-3).
 *
 * Связь только исходящая (ADR-0004): control plane внутрь экземпляра не ходит.
 * Токен выдаётся один раз при регистрации экземпляра в панели и хранится
 * в конфигурации развёртывания (env, в фазе P — Vault).
 *
 * @param url      базовый адрес control plane; пусто — heartbeat выключен
 * @param token    токен экземпляра для заголовка X-Instance-Token
 * @param interval период отправки; ТЗ допускает 1–5 мин
 */
@ConfigurationProperties(prefix = "dwh.control-plane")
public record CpClientProperties(String url, String token, Duration interval) {

    public CpClientProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            interval = Duration.ofMinutes(5);
        }
    }

    /**
     * Экземпляр может работать и без control plane — на изолированном контуре
     * клиента или в разработке. Пока адрес и токен не заданы, отправка молчит.
     */
    public boolean enabled() {
        return url != null && !url.isBlank() && token != null && !token.isBlank();
    }
}
