package com.smartup24.cms.instance.config.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.RepairResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * One-off repair of the migration history before migrating (plan 10/10, item 0.5; runbook
 * {@code docs/ops/migration-repair.md}).
 *
 * <p>Before the manifest made migrations immutable, V100 was edited and V101 deleted after they had been applied. A
 * database that applied them refuses to start: {@code flyway validate} finds a changed checksum and a migration that
 * no longer exists. With {@code SMC_MIGRATE_REPAIR=true} the migrate profile first runs {@code flyway repair}, which
 * realigns the checksums of applied migrations with the files and marks the missing ones as deleted, then migrates as
 * usual. The data those migrations created stays. The switch is meant for one run and is removed afterwards.
 */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
@ConditionalOnProperty(name = "smc.migrate.repair", havingValue = "true")
public class FlywayRepairConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairConfiguration.class);

    @Bean
    FlywayMigrationStrategy repairThenMigrate() {
        return FlywayRepairConfiguration::repairThenMigrate;
    }

    static void repairThenMigrate(Flyway flyway) {
        RepairResult repair = flyway.repair();
        log.warn(
                "migration_history_repaired aligned={} deleted={} removed={}",
                repair.migrationsAligned.size(),
                repair.migrationsDeleted.size(),
                repair.migrationsRemoved.size());
        flyway.migrate();
    }
}
