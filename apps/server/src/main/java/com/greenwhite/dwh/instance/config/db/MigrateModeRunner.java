package com.greenwhite.dwh.instance.config.db;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Профиль migrate: Flyway уже применил миграции при старте контекста
 * (в этом профиле spring.flyway.enabled=true) — раннер печатает итог
 * и завершает процесс. Приложение в этом режиме НЕ обслуживает запросы.
 */
@Component
@Profile("migrate")
public class MigrateModeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrateModeRunner.class);

    private final ApplicationContext context;
    private final Flyway flyway;

    public MigrateModeRunner(ApplicationContext context, Flyway flyway) {
        this.context = context;
        this.flyway = flyway;
    }

    @Override
    public void run(ApplicationArguments args) {
        var current = flyway.info().current();
        log.info("Миграции применены. Текущая версия схемы: {} ({})",
                current != null ? current.getVersion() : "<пусто>",
                current != null ? current.getDescription() : "-");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
