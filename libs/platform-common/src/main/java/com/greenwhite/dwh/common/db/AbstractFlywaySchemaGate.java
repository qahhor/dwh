package com.greenwhite.dwh.common.db;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Базовый абстрактный шлюз проверки схемы БД Flyway перед стартом приложения (Zero-Downtime Expand-Contract).
 */
public abstract class AbstractFlywaySchemaGate {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final DataSource dataSource;
    private final boolean enabled;
    private final String location;
    private final String moduleName;

    protected AbstractFlywaySchemaGate(DataSource dataSource, boolean enabled, String location, String moduleName) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.location = location != null ? location : "classpath:db/migration";
        this.moduleName = moduleName != null ? moduleName : "Platform";
    }

    @PostConstruct
    public void verifySchemaMatchesApplication() {
        if (!enabled) {
            log.warn("Schema-gate [{}] ОТКЛЮЧЁН — допустимо только в тестах", moduleName);
            return;
        }
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .load();
        var result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            String details = result.invalidMigrations.stream()
                    .map(m -> m.version + " " + m.description + ": " + m.errorDetails.errorMessage)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse(result.getAllErrorMessages());
            throw new IllegalStateException(
                    "Схема БД [" + moduleName + "] не соответствует приложению. Запустите миграции: "
                            + "--spring.profiles.active=migrate. Детали: " + details);
        }
        var current = flyway.info().current();
        log.info("Schema-gate [{}]: версия схемы {} соответствует приложению",
                moduleName, current != null ? current.getVersion() : "<пусто>");
    }
}
