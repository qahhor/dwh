package com.smartup24.cms.instance.fnd.migration;

import com.smartup24.cms.instance.fnd.FndPref;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Программное применение миграций обеих БД (промпт 02 п.10: «мигрируй → gate → старт»; AC-1, AC-3).
 * Автомиграция при старте приложения выключена ({@code spring.flyway.enabled=false}); этот класс
 * вызывают {@link MigrateMain} (поставка), dev-запуск и тесты.
 */
public final class FndMigrator {

    private static final Logger log = LoggerFactory.getLogger(FndMigrator.class);

    private FndMigrator() {}

    /** Миграции OLTP из каталога каркаса {@code db/migration} (миграции каркаса + наши V1xx); возвращает число применённых файлов. */
    public static int migrateOltp(DataSource oltp) {
        return migrate(oltp, FndPref.OLTP_MIGRATIONS, "oltp");
    }

    /** Миграции pg-dwh из {@code db/dwh}; возвращает число применённых файлов. */
    public static int migrateDwh(DataSource dwh) {
        return migrate(dwh, FndPref.DWH_MIGRATIONS, "dwh");
    }

    /**
     * Миграции всегда применяются в UTC: {@code V011} каркаса создаёт партиции {@code audit_log}
     * по date-литералам, и при другой зоне соединения границы съезжают (см. FlywayUtcConfiguration каркаса).
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
