package com.smartup24.cms.instance.warehouse.migration;

import com.smartup24.cms.instance.common.module.ModuleMigrations;
import com.smartup24.cms.instance.warehouse.WarehousePref;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the migrations of both databases from code, following the deployment order "migrate, then check the schema
 * version, then start". Automatic migration on application startup is off ({@code spring.flyway.enabled=false});
 * this class is called by {@link MigrateMain} (deployment), by the dev launch and by tests.
 */
public final class Migrator {

    private static final Logger log = LoggerFactory.getLogger(Migrator.class);

    private Migrator() {}

    /**
     * Migrates OLTP from the framework's {@code db/migration} directory (the framework's migrations plus our V1xx
     * ones), then the migrations of the modules on the classpath; returns the number of files applied.
     */
    public static int migrateOltp(DataSource oltp) {
        int platform = migrate(oltp, WarehousePref.OLTP_MIGRATIONS, "oltp");
        // The modules that bring migrations of their own follow the platform's (ADR-0033, 6.5).
        return platform + ModuleMigrations.migrate(oltp, Migrator.class.getClassLoader());
    }

    /** Migrates pg-dwh from {@code db/dwh}; returns the number of files applied. */
    public static int migrateDwh(DataSource dwh) {
        return migrate(dwh, WarehousePref.WAREHOUSE_MIGRATIONS, "dwh");
    }

    /**
     * Migrations always run in UTC: the framework's {@code V011} creates {@code audit_log} partitions from date
     * literals, and with another connection time zone the partition bounds shift (see the framework's
     * FlywayUtcConfiguration).
     */
    static final String UTC_INIT_SQL = "set time zone 'UTC'";

    private static int migrate(DataSource dataSource, String location, String db) {
        // As in FlywayUtcConfiguration: a concurrent index file runs outside a transaction (ADR-0020, rule 8) and
        // Flyway's lock is taken at session level, so the build does not wait for it.
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .initSql(UTC_INIT_SQL)
                .mixed(true)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .locations("classpath:" + location)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .load();
        MigrateResult result = flyway.migrate();
        log.info(
                "migrations_applied db={} count={} schemaVersion={}",
                db,
                result.migrationsExecuted,
                result.targetSchemaVersion);
        return result.migrationsExecuted;
    }
}
