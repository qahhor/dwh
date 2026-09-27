package com.smartup24.cms.instance.db;

import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository;
import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.support.TestDatabases;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 10/10, item 1.7: a database of the previous release, with data in it, upgrades to the current schema.
 *
 * <p>Runs on the embedded PostgreSQL, so it never skips for want of Docker. {@link #PREVIOUS_RELEASE} is the schema
 * of the last build that went out (main 294202db, before the 10/10 plan); it moves with every release.
 */
class ReleaseUpgradeIntegrationTest {

    /** The last migration of the previous release. */
    static final String PREVIOUS_RELEASE = "123";

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    @DisplayName("1.7: a release-" + PREVIOUS_RELEASE + " database with data upgrades, validates and keeps its rows")
    void previousReleaseUpgradesWithItsData() throws IOException {
        String name = "upgrade_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TestDatabases.createDatabase(name);
        DataSource ds = TestDatabases.database(name);
        JdbcClient jdbc = JdbcClient.create(ds);

        flyway(ds).target(PREVIOUS_RELEASE).load().migrate();
        seedPreviousRelease(jdbc);

        int applied = flyway(ds).load().migrate().migrationsExecuted;

        assertThat(applied).as("every migration after the release runs once").isEqualTo(migrationsAfterRelease());
        assertThat(flyway(ds).load().validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway(ds).load().migrate().migrationsExecuted).as("a second run changes nothing").isZero();

        // The data of the release is still there.
        assertThat(count(jdbc, "select count(*) from md_users where login = 'upgrade_probe'")).isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from md_settings where key = 'upgrade.probe' and value = 'kept'"))
                .isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from security_events where event_type = 'UPGRADE_PROBE'")).isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from audit_log where table_name = 'upgrade_probe'"))
                .as("audit rows of past and current months").isEqualTo(2);

        // What the migrations after the release had to do to that data.
        assertThat(count(jdbc, "select count(*) from idempotency_keys where response_body ->> 'rawSecretToken' is not null"))
                .as("V124 purged the stored secret").isZero();
        assertThat(count(jdbc, "select count(*) from idempotency_keys where response_body ->> 'kept' = 'yes'"))
                .as("V124 kept the ordinary answer").isEqualTo(1);
        assertThat(new AuditPartitionRepository(jdbc).covers(LocalDate.now(ZoneOffset.UTC).plusDays(31)))
                .as("V127 left a runway of daily partitions").isTrue();
        assertThat(count(jdbc, "select count(*) from audit_log_default")).as("nothing stranded in default").isZero();
    }

    /** Rows in the tables the later migrations touch, written as the release wrote them. */
    private static void seedPreviousRelease(JdbcClient jdbc) {
        Long userId = jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state)
                        values ('Upgrade Probe', 'upgrade_probe', 'upgrade_probe@test.local', 'hash', 'A')
                        returning id
                        """).query(Long.class).single();
        jdbc.sql("insert into md_settings (user_id, key, value) values (null, 'upgrade.probe', 'kept')").update();
        jdbc.sql("""
                insert into security_events (event_type, user_id, ip, details)
                values ('UPGRADE_PROBE', :userId, cast('127.0.0.1' as inet), '{}'::jsonb)
                """).param("userId", userId).update();
        jdbc.sql("""
                insert into audit_log (table_name, row_pk, event, changed_at)
                values ('upgrade_probe', '1', 'I', now()),
                       ('upgrade_probe', '2', 'I', timestamptz '2026-08-15 10:00:00+00')
                """).update();
        for (String body : new String[]{"{\"rawSecretToken\": \"dwh_probe\"}", "{\"kept\": \"yes\"}"}) {
            jdbc.sql("""
                            insert into idempotency_keys (key, user_id, request_hash, response_status, response_body)
                            values (:key, :userId, 'hash', 201, cast(:body as jsonb))
                            """)
                    .param("key", UUID.randomUUID())
                    .param("userId", userId)
                    .param("body", body)
                    .update();
        }
    }

    private static int migrationsAfterRelease() throws IOException {
        int release = Integer.parseInt(PREVIOUS_RELEASE);
        return (int) Arrays.stream(new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql"))
                .map(resource -> VERSION.matcher(resource.getFilename()))
                .filter(Matcher::matches)
                .filter(matcher -> Integer.parseInt(matcher.group(1)) > release)
                .count();
    }

    private static FluentConfiguration flyway(DataSource ds) {
        return FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds)
                .locations("classpath:db/migration");
    }

    private static long count(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
