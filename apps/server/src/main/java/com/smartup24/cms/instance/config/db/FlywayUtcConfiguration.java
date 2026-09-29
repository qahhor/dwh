package com.smartup24.cms.instance.config.db;

import java.util.Map;
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

    /** Flyway's PostgreSQL setting: a session-level lock, which a concurrent index build does not wait for. */
    public static final String TRANSACTIONAL_LOCK = "flyway.postgresql.transactional.lock";

    @Bean
    FlywayConfigurationCustomizer flywayUtcCustomizer() {
        return FlywayUtcConfiguration::configure;
    }

    /**
     * UTC for every migration; and {@code mixed}: a file that builds an index concurrently also sets its timeouts in
     * its header (ADR-0020, rule 8), so Flyway runs that one file outside a transaction. Every other file still runs
     * in one. A concurrent build waits for every open transaction, Flyway's own included, so Flyway takes its lock at
     * session level instead of in a transaction.
     */
    public static FluentConfiguration configure(FluentConfiguration configuration) {
        return configuration.initSql(UTC_INIT_SQL).mixed(true).configuration(Map.of(TRANSACTIONAL_LOCK, "false"));
    }
}
