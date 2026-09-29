package com.smartup24.cms.instance.fnd.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Подключение к {@code pg-dwh} (промпт 02 п.18). Таймаут соединения обязателен: дефолта «без таймаута»
 * нет (AC-36), отсутствие свойства {@code app.dwh.connect-timeout} — красный старт.
 *
 * <p>Таймауты запросов (P0 DWH): {@code statement-timeout} — предел одного запроса и простоя внутри
 * транзакции для всего пула (чтение витрин, запись raw пачками); {@code maintenance-statement-timeout}
 * — для заданий обслуживания, которые проходят весь raw ({@link FndDwhMaintenance}). У обоих есть
 * значения по умолчанию: без них пул уже не бывает «без таймаута».
 *
 * <p>{@code raw-write-timeout} bounds one streamed write of a load into raw (plan 10/10, item 3.9): a single
 * {@code COPY} lasts as long as the file takes to parse, minutes for a million rows, so the pool limit would cut it.
 */
@Validated
@ConfigurationProperties(prefix = "app.dwh")
public record DwhDataSourceProperties(
        @NotBlank String url,
        @NotBlank String username,
        String password,
        @NotNull Duration connectTimeout,
        @DefaultValue("60s") Duration statementTimeout,
        @DefaultValue("30m") Duration maintenanceStatementTimeout,
        @DefaultValue("30m") Duration rawWriteTimeout) {

    public DwhDataSourceProperties {
        requirePositive("app.dwh.connect-timeout", connectTimeout);
        requirePositive("app.dwh.statement-timeout", statementTimeout);
        requirePositive("app.dwh.maintenance-statement-timeout", maintenanceStatementTimeout);
        requirePositive("app.dwh.raw-write-timeout", rawWriteTimeout);
    }

    private static void requirePositive(String name, Duration value) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(name + " должен быть положительным");
        }
    }
}
