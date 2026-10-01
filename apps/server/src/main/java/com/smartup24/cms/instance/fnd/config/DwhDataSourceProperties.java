package com.smartup24.cms.instance.fnd.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Connection settings for {@code pg-dwh}. The connect timeout is mandatory: there is no "no timeout" default,
 * and a missing {@code warehouse.connect-timeout} property makes startup fail.
 *
 * <p>Query timeouts: {@code statement-timeout} limits a single query and idle time inside a transaction for the
 * whole pool (mart reads, raw writes in batches); {@code maintenance-statement-timeout} applies to maintenance jobs
 * that scan all of raw ({@link FndDwhMaintenance}). Both have defaults, so the pool never runs without a timeout.
 *
 * <p>{@code raw-write-timeout} bounds one streamed write of a load into raw (plan 10/10, item 3.9): a single
 * {@code COPY} lasts as long as the file takes to parse, minutes for a million rows, so the pool limit would cut it.
 */
@Validated
@ConfigurationProperties(prefix = "warehouse")
public record DwhDataSourceProperties(
        @NotBlank String url,
        @NotBlank String username,
        String password,
        @NotNull Duration connectTimeout,
        @DefaultValue("60s") Duration statementTimeout,
        @DefaultValue("30m") Duration maintenanceStatementTimeout,
        @DefaultValue("30m") Duration rawWriteTimeout) {

    public DwhDataSourceProperties {
        requirePositive("warehouse.connect-timeout", connectTimeout);
        requirePositive("warehouse.statement-timeout", statementTimeout);
        requirePositive("warehouse.maintenance-statement-timeout", maintenanceStatementTimeout);
        requirePositive("warehouse.raw-write-timeout", rawWriteTimeout);
    }

    private static void requirePositive(String name, Duration value) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(name + " должен быть положительным");
        }
    }
}
