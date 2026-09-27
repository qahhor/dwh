package com.smartup24.cms.instance.config.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.TestDatabases;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 0.5: a database from before the manifest (V100 applied with its old text, V101 applied and later
 * deleted) fails validation, and the one-off repair of the migrate profile makes it pass without losing data.
 */
class FlywayRepairIntegrationTest {

    @Test
    @DisplayName("0.5: repair realigns an edited migration and forgets a deleted one; validation passes afterwards")
    void repairAcceptsTheV101Era() {
        DataSource ds = TestDatabases.migratedCopy("dwh_migration_repair");
        JdbcClient jdbc = JdbcClient.create(ds);
        // The state of a stand migrated before 2026-09-20: another checksum for V100 and a V101 row.
        jdbc.sql("update flyway_schema_history set checksum = checksum + 1 where version = '100'")
                .update();
        jdbc.sql("""
                insert into flyway_schema_history (installed_rank, version, description, type, script, checksum,
                                                   installed_by, execution_time, success)
                select max(installed_rank) + 1, '101', 'fnd system user', 'SQL', 'V101__fnd_system_user.sql', 1,
                       current_user, 1, true
                from flyway_schema_history
                """).update();
        long users = jdbc.sql("select count(*) from md_users").query(Long.class).single();
        Flyway flyway = FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.validateWithResult().validationSuccessful)
                .as("the stand refuses to start")
                .isFalse();

        FlywayRepairConfiguration.repairThenMigrate(flyway);

        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.sql("select count(*) from md_users").query(Long.class).single())
                .as("repair touches the history only")
                .isEqualTo(users);
    }
}
