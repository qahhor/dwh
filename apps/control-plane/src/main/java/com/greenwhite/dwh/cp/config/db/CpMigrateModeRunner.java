package com.greenwhite.dwh.cp.config.db;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Профиль migrate control plane: применить миграции и завершить процесс. */
@Component
@Profile("migrate")
public class CpMigrateModeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CpMigrateModeRunner.class);

    private final ApplicationContext context;
    private final Flyway flyway;

    public CpMigrateModeRunner(ApplicationContext context, Flyway flyway) {
        this.context = context;
        this.flyway = flyway;
    }

    @Override
    public void run(ApplicationArguments args) {
        var current = flyway.info().current();
        log.info("Миграции control plane применены. Версия схемы: {} ({})",
                current != null ? current.getVersion() : "<пусто>",
                current != null ? current.getDescription() : "-");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
