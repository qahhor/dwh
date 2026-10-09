package com.smartup24.cms.common.db;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base gate that checks the Flyway database schema before the application starts (zero-downtime expand-contract).
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
            log.warn("Schema gate [{}] is disabled: acceptable in tests only", moduleName);
            return;
        }
        Flyway flyway =
                Flyway.configure().dataSource(dataSource).locations(location).load();
        var result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            String details = result.invalidMigrations.stream()
                    .map(m -> m.version + " " + m.description + ": " + m.errorDetails.errorMessage)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse(result.getAllErrorMessages());
            throw new IllegalStateException(
                    "The database schema [" + moduleName + "] does not match the application. Run the migrations: "
                            + "--spring.profiles.active=migrate. Details: " + details);
        }
        var current = flyway.info().current();
        log.info(
                "Schema gate [{}]: schema version {} matches the application",
                moduleName,
                current != null ? current.getVersion() : "<empty>");
    }
}
