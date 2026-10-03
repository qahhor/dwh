package com.smartup24.cms.instance.config.db;

import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.module.ModuleMigrations;
import javax.sql.DataSource;
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
 * The migrate profile: Flyway has already applied migrations at context startup
 * (spring.flyway.enabled=true in this profile); the runner applies the migrations of the modules that bring their own
 * (ADR-0033, 6.5), prints the result and exits the process. In this mode the application does NOT serve requests.
 */
@Component
@Profile("migrate")
public class MigrateModeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrateModeRunner.class);

    private final ApplicationContext context;
    private final Flyway flyway;
    private final DataSource dataSource;
    private final ModuleCatalog modules;

    public MigrateModeRunner(ApplicationContext context, Flyway flyway, DataSource dataSource, ModuleCatalog modules) {
        this.context = context;
        this.flyway = flyway;
        this.dataSource = dataSource;
        this.modules = modules;
    }

    @Override
    public void run(ApplicationArguments args) {
        ClassLoader loader = context.getClassLoader();
        int moduleFiles = ModuleMigrations.migrate(
                dataSource, modules.manifests(), loader != null ? loader : MigrateModeRunner.class.getClassLoader());
        log.info("module_migrations_total count={}", moduleFiles);
        var current = flyway.info().current();
        log.info(
                "Миграции применены. Текущая версия схемы: {} ({})",
                current != null ? current.getVersion() : "<пусто>",
                current != null ? current.getDescription() : "-");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
