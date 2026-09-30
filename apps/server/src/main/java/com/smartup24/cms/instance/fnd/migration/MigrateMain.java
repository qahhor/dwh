package com.smartup24.cms.instance.fnd.migration;

import java.util.Map;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The "migrate" step of deployment: applies the OLTP and pg-dwh migrations and exits.
 * From the fat jar, run {@code java -cp app.jar -Dloader.main=com.smartup24.cms.instance.fnd.migration.MigrateMain
 * org.springframework.boot.loader.launch.PropertiesLauncher}; from the extracted image
 * ({@code -Djarmode=tools extract}, the layout of the framework's {@code Dockerfile}), run
 * {@code java -cp app.jar com.smartup24.cms.instance.fnd.migration.MigrateMain}.
 * Connections come from the environment only: {@code DWH_DB_URL/USER/PASSWORD} (OLTP)
 * and {@code DWH_DATA_DB_URL/USER/PASSWORD} (pg-dwh).
 * {@code DWH_MIGRATE_SCOPE} sets the scope: {@code all} (the default) migrates both databases; {@code dwh} migrates
 * only pg-dwh, leaving OLTP to the framework's standard {@code migrate} profile.
 */
public final class MigrateMain {

    private static final String SCOPE_KEY = "DWH_MIGRATE_SCOPE";
    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_DWH = "dwh";

    private MigrateMain() {}

    public static void main(String[] args) {
        // The migration container reports on stdout before any logging is configured.
        // CHECKSTYLE.OFF-ID: noSystemOut
        System.out.println(run(System.getenv()));
        // CHECKSTYLE.ON-ID: noSystemOut
    }

    static String run(Map<String, String> env) {
        String scope = env.getOrDefault(SCOPE_KEY, SCOPE_ALL);
        if (!SCOPE_ALL.equals(scope) && !SCOPE_DWH.equals(scope)) {
            throw new IllegalStateException(SCOPE_KEY + ": ожидается all или dwh, получено " + scope);
        }
        int oltp = SCOPE_ALL.equals(scope)
                ? FndMigrator.migrateOltp(dataSource(env, "DWH_DB_URL", "DWH_DB_USER", "DWH_DB_PASSWORD"))
                : 0;
        int dwh =
                FndMigrator.migrateDwh(dataSource(env, "DWH_DATA_DB_URL", "DWH_DATA_DB_USER", "DWH_DATA_DB_PASSWORD"));
        return "migrations applied: scope=" + scope + " oltp=" + oltp + " dwh=" + dwh;
    }

    private static DriverManagerDataSource dataSource(
            Map<String, String> env, String urlKey, String userKey, String passwordKey) {
        String url = env.get(urlKey);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Не задана переменная окружения " + urlKey);
        }
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        ds.setUsername(env.getOrDefault(userKey, "dwh"));
        ds.setPassword(env.getOrDefault(passwordKey, ""));
        return ds;
    }
}
