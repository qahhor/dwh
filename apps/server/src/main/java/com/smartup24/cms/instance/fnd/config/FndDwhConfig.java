package com.smartup24.cms.instance.fnd.config;

import com.smartup24.cms.instance.common.health.ReadinessChecks;
import com.smartup24.cms.instance.fnd.FndPref;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The second {@link DataSource}, {@code pg-dwh}. This is the only place that creates it, and the {@code "dwh"}
 * qualifier never leaves the {@code fnd} package. The pool does not check the connection at startup: pg-dwh
 * availability is the job of {@code DwhSchemaVersionGate} and the facades.
 *
 * <p>{@code defaultCandidate = false}: the second database's beans are visible only through the {@code "dwh"}
 * qualifier. Otherwise the framework, which injects {@code DataSource}/{@code JdbcClient} by type, would find two
 * candidates and the context would fail to start, both in tests and in production.
 */
@Configuration
@EnableConfigurationProperties(DwhDataSourceProperties.class)
public class FndDwhConfig {

    @Bean(name = "dwhDataSource", destroyMethod = "close", defaultCandidate = false)
    @Qualifier(FndPref.DWH)
    public DataSource dwhDataSource(DwhDataSourceProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("dwh");
        ds.setJdbcUrl(props.url());
        ds.setUsername(props.username());
        ds.setPassword(props.password());
        long timeoutMs = props.connectTimeout().toMillis();
        ds.setConnectionTimeout(Math.max(timeoutMs, 250));
        ds.setInitializationFailTimeout(-1);
        ds.setMaximumPoolSize(4);
        // PostgreSQL driver: connectTimeout/loginTimeout are in seconds, at least 1
        long seconds = Math.max(1, (timeoutMs + 999) / 1000);
        ds.addDataSourceProperty("connectTimeout", String.valueOf(seconds));
        ds.addDataSourceProperty("loginTimeout", String.valueOf(seconds));
        // Without a limit, one heavy query or an abandoned transaction held a connection forever, and the
        // pool has only four. The server itself cuts off a long query and a transaction left idle...
        long statementMs = props.statementTimeout().toMillis();
        ds.addDataSourceProperty(
                "options",
                "-c statement_timeout=" + statementMs + " -c idle_in_transaction_session_timeout=" + statementMs);
        // ...while socketTimeout (in seconds) is the last safeguard against a dead network: it is longer than
        // any server-side limit, maintenance jobs included, and fires only when the server goes silent.
        long longestMs = Math.max(
                statementMs,
                Math.max(
                        props.maintenanceStatementTimeout().toMillis(),
                        props.rawWriteTimeout().toMillis()));
        ds.addDataSourceProperty("socketTimeout", String.valueOf((longestMs + 999) / 1000 + 60));
        return ds;
    }

    /**
     * pg-dwh health for monitoring (plan 10/10, item 0.7), not a readiness member: the DWH module degrades alone.
     * Declared here: the pg-dwh data source does not leave this package.
     */
    @Bean
    public HealthIndicator dwhHealthIndicator(
            @Qualifier(FndPref.DWH) DataSource dwhDataSource,
            @Value("${dwh.system.health-timeout:2s}") String timeout) {
        // Parsed here: the context of the pg-dwh configuration test has no conversion service.
        Duration deadline = DurationStyle.detectAndParse(timeout);
        JdbcClient jdbc = JdbcClient.create(dwhDataSource);
        return () -> ReadinessChecks.within(deadline, () -> {
            jdbc.sql("select 1").query().singleValue();
            return Health.up().build();
        });
    }

    @Bean
    public FndDwhMaintenance fndDwhMaintenance(
            @Qualifier(FndPref.DWH) DataSource dwhDataSource, DwhDataSourceProperties props) {
        return new FndDwhMaintenance(dwhDataSource, props.maintenanceStatementTimeout());
    }

    @Bean(name = "dwhJdbcClient", defaultCandidate = false)
    @Qualifier(FndPref.DWH)
    public JdbcClient dwhJdbcClient(@Qualifier(FndPref.DWH) DataSource dwhDataSource) {
        return JdbcClient.create(dwhDataSource);
    }
}
