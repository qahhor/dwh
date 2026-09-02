package com.greenwhite.dwh.instance.config.db;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps timestamp partition boundaries independent from the host timezone.
 *
 * <p>The immutable V001 migration uses explicit UTC boundaries while V011
 * uses PostgreSQL date literals. PostgreSQL resolves those literals in the
 * connection timezone, so Flyway must always migrate in UTC.</p>
 */
@Configuration(proxyBeanMethods = false)
public class FlywayUtcConfiguration {

    static final String UTC_INIT_SQL = "set time zone 'UTC'";

    @Bean
    FlywayConfigurationCustomizer flywayUtcCustomizer() {
        return FlywayUtcConfiguration::configure;
    }

    public static FluentConfiguration configure(FluentConfiguration configuration) {
        return configuration.initSql(UTC_INIT_SQL);
    }
}
