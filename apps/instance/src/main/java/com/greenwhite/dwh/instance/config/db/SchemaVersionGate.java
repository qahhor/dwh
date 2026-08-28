package com.greenwhite.dwh.instance.config.db;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Schema-gate (FR-INST-2, ADR-0007 разд. 2.3): приложение ОТКАЗЫВАЕТСЯ стартовать,
 * если схема БД не совпадает с миграциями на classpath — есть неприменённые,
 * применены неизвестные (БД новее приложения) или расходятся контрольные суммы.
 * Мигрировать самостоятельно приложение не может — только профиль migrate.
 */
@Component
@Profile("!migrate")
public class SchemaVersionGate {

    private static final Logger log = LoggerFactory.getLogger(SchemaVersionGate.class);

    private final DataSource dataSource;
    private final boolean enabled;

    public SchemaVersionGate(DataSource dataSource,
                             @Value("${dwh.schema-gate.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @PostConstruct
    public void verifySchemaMatchesApplication() {
        if (!enabled) {
            log.warn("Schema-gate ОТКЛЮЧЁН (dwh.schema-gate.enabled=false) — допустимо только в тестах");
            return;
        }
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        var result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            String details = result.invalidMigrations.stream()
                    .map(m -> m.version + " " + m.description + ": " + m.errorDetails.errorMessage)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse(result.getAllErrorMessages());
            throw new IllegalStateException(
                    "Схема БД не соответствует приложению (FR-INST-2). Запустите миграции: "
                            + "--spring.profiles.active=migrate. Детали: " + details);
        }
        var current = flyway.info().current();
        log.info("Schema-gate: версия схемы {} соответствует приложению",
                current != null ? current.getVersion() : "<пусто>");
    }
}
