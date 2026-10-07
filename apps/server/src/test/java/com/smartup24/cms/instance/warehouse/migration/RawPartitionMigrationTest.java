package com.smartup24.cms.instance.warehouse.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.instance.warehouse.WarehousePref;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 7.8: an installation of pg-dwh at V003 (raw.rows a plain table, rows in it) upgrades to V004, where
 * raw.rows is partitioned by load with its key, file index and immutability trigger.
 */
class RawPartitionMigrationTest {

    private static final String DB = "smc_dwh_v003_upgrade";

    @Test
    @DisplayName("7.8: V003 with rows upgrades to a raw.rows partitioned by list on load_id")
    void v003UpgradesToPartitionedRaw() {
        TestDatabases.createDatabase(DB);
        DataSource dwh = TestDatabases.database(DB);
        JdbcClient jdbc = JdbcClient.create(dwh);
        flyway(dwh, "3").migrate();
        jdbc.sql("insert into raw.rows (load_id, row_no, fields) values (1, 1, '{}'::jsonb)")
                .update();

        assertThat(Migrator.migrateDwh(dwh)).isPositive();

        assertThat(jdbc.sql("select partstrat from pg_partitioned_table where partrelid = 'raw.rows'::regclass")
                        .query(String.class)
                        .single())
                .isEqualTo("l");
        assertThat(jdbc.sql("select count(*) from raw.rows").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from pg_indexes where schemaname = 'raw' and tablename = 'rows'")
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
        assertThat(jdbc.sql("select count(*) from pg_trigger where tgrelid = 'raw.rows'::regclass"
                                + " and tgname = 'raw_rows_immutable'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    private static Flyway flyway(DataSource dwh, String target) {
        return Flyway.configure()
                .dataSource(dwh)
                .initSql(Migrator.UTC_INIT_SQL)
                .locations("classpath:" + WarehousePref.WAREHOUSE_MIGRATIONS)
                .target(target)
                .load();
    }
}
