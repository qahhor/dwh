package com.smartup24.cms.instance.warehouse.migration;

import java.util.Map;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The "migrate" step of deployment: applies the OLTP and pg-dwh migrations and exits.
 * From the fat jar, run {@code java -cp app.jar -Dloader.main=com.smartup24.cms.instance.warehouse.migration.MigrateMain
 * org.springframework.boot.loader.launch.PropertiesLauncher}; from the extracted image
 * ({@code -Djarmode=tools extract}, the layout of the framework's {@code Dockerfile}), run
 * {@code java -cp app.jar com.smartup24.cms.instance.warehouse.migration.MigrateMain}.
 * Connections come from the environment only, under the names the server reads (ADR-0027):
 * {@code DB_URL/DB_USER/DB_PASSWORD} (OLTP) and {@code WAREHOUSE_URL/WAREHOUSE_USERNAME/WAREHOUSE_PASSWORD}
 * (pg-dwh). {@code SMC_MIGRATE_SCOPE} sets the scope: {@code all} (the default) migrates both databases;
 * {@code warehouse} migrates only pg-dwh, leaving OLTP to the framework's standard {@code migrate} profile.
 */
public final class MigrateMain {

    private static final String SCOPE_KEY = "SMC_MIGRATE_SCOPE";
    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_WAREHOUSE = "warehouse";

    private MigrateMain() {}

    public static void main(String[] args) {
        // The migration container reports on stdout before any logging is configured.
        // CHECKSTYLE.OFF-ID: noSystemOut
        System.out.println(run(System.getenv()));
        // CHECKSTYLE.ON-ID: noSystemOut
    }

    static String run(Map<String, String> env) {
        String scope = env.getOrDefault(SCOPE_KEY, SCOPE_ALL);
        if (!SCOPE_ALL.equals(scope) && !SCOPE_WAREHOUSE.equals(scope)) {
            throw new IllegalStateException(SCOPE_KEY + ": expected all or warehouse, got " + scope);
        }
        int oltp =
                SCOPE_ALL.equals(scope) ? Migrator.migrateOltp(dataSource(env, "DB_URL", "DB_USER", "DB_PASSWORD")) : 0;
        int dwh = Migrator.migrateDwh(dataSource(env, "WAREHOUSE_URL", "WAREHOUSE_USERNAME", "WAREHOUSE_PASSWORD"));
        return "migrations applied: scope=" + scope + " oltp=" + oltp + " dwh=" + dwh;
    }

    private static DriverManagerDataSource dataSource(
            Map<String, String> env, String urlKey, String userKey, String passwordKey) {
        String url = env.get(urlKey);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("The environment variable is not set: " + urlKey);
        }
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        ds.setUsername(env.getOrDefault(userKey, "dwh"));
        ds.setPassword(env.getOrDefault(passwordKey, ""));
        return ds;
    }
}
