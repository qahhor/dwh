package com.smartup24.cms.instance.common.module;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the migrations of the modules that bring their own (ADR-0033, 6.5): after the platform's, each module with a
 * Flyway history of its own ({@code flyway_module_<code>}), every module after the modules it needs. The module numbers
 * its {@code V} files itself; the platform's numbering does not apply to them.
 */
public final class ModuleMigrations {

    private static final Logger log = LoggerFactory.getLogger(ModuleMigrations.class);

    /** As the platform's migrations: UTC, a session-level lock, one file outside a transaction when it says so. */
    private static final String UTC_INIT_SQL = "set time zone 'UTC'";

    private ModuleMigrations() {}

    /** Migrates every module of the list that has migrations; returns the number of files applied. */
    public static int migrate(DataSource dataSource, List<ModuleManifest> manifests, ClassLoader loader) {
        int applied = 0;
        for (ModuleManifest manifest : ModuleManifests.inDependencyOrder(manifests)) {
            String location = manifest.migrations();
            if (location == null) continue;
            Flyway flyway = Flyway.configure(loader)
                    .dataSource(dataSource)
                    .initSql(UTC_INIT_SQL)
                    .mixed(true)
                    .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                    .locations("classpath:" + location)
                    .table(manifest.historyTable())
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .validateOnMigrate(true)
                    .load();
            MigrateResult result = flyway.migrate();
            log.info(
                    "module_migrations_applied module={} count={} schemaVersion={}",
                    manifest.code(),
                    result.migrationsExecuted,
                    result.targetSchemaVersion);
            applied += result.migrationsExecuted;
        }
        return applied;
    }

    /** Migrates the modules whose manifests are on the class loader's classpath. */
    public static int migrate(DataSource dataSource, ClassLoader loader) {
        return migrate(dataSource, ModuleManifests.read(loader), loader);
    }
}
