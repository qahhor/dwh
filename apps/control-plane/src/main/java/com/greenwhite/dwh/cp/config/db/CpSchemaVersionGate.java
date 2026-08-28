package com.greenwhite.dwh.cp.config.db;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Schema-gate control plane (паритет с instance, FR-INST-2 / ADR-0007 разд. 2.3):
 * приложение отказывается стартовать, если схема БД не соответствует миграциям
 * на classpath. Мигрировать самостоятельно не может — только профиль migrate.
 *
 * Дублирует логику instance осознанно: приложения деплоятся раздельно и имеют
 * независимые наборы миграций. При появлении третьего приложения — вынести в libs.
 */
@Component
@Profile("!migrate")
public class CpSchemaVersionGate {

    private static final Logger log = LoggerFactory.getLogger(CpSchemaVersionGate.class);

    private final DataSource dataSource;
    private final boolean enabled;

    public CpSchemaVersionGate(DataSource dataSource,
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
            throw new IllegalStateException(
                    "Схема БД control plane не соответствует приложению. Запустите миграции: "
                            + "--spring.profiles.active=migrate. Детали: " + result.getAllErrorMessages());
        }
        var current = flyway.info().current();
        log.info("Schema-gate (control plane): версия схемы {} соответствует приложению",
                current != null ? current.getVersion() : "<пусто>");
    }
}
