package com.smartup24.cms.instance.warehouse.migration;

import com.smartup24.cms.instance.common.env.LegacyConfigNames;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * The old names ({@code DWH_DB_*}, {@code DWH_DATA_DB_*}, {@code DWH_MIGRATE_SCOPE}, the scope {@code dwh}) are read
 * until {@link LegacyConfigNames#SUNSET}, with a warning. The class name of earlier releases,
 * {@code com.smartup24.cms.instance.fnd.migration.MigrateMain}, starts the same step until then (ADR-0030).
 */
public class MigrateMain {

    private static final String SCOPE_KEY = "SMC_MIGRATE_SCOPE";
    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_WAREHOUSE = "warehouse";
    private static final String LEGACY_SCOPE_WAREHOUSE = "dwh";

    /** Only the alias of the earlier class name extends it; the step itself is static. */
    protected MigrateMain() {}

    public static void main(String[] args) {
        // The migration container reports on stdout before any logging is configured.
        // CHECKSTYLE.OFF-ID: noSystemOut
        System.out.println(run(System.getenv()));
        // CHECKSTYLE.ON-ID: noSystemOut
    }

    static String run(Map<String, String> environment) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> env = withCurrentNames(environment, warnings);
        String scope = env.getOrDefault(SCOPE_KEY, SCOPE_ALL);
        if (LEGACY_SCOPE_WAREHOUSE.equals(scope)) {
            warnings.add(LegacyConfigNames.warning(SCOPE_KEY + "=" + scope, SCOPE_KEY + "=" + SCOPE_WAREHOUSE));
            scope = SCOPE_WAREHOUSE;
        }
        if (!SCOPE_ALL.equals(scope) && !SCOPE_WAREHOUSE.equals(scope)) {
            throw new IllegalStateException(SCOPE_KEY + ": ожидается all или warehouse, получено " + scope);
        }
        int oltp =
                SCOPE_ALL.equals(scope) ? Migrator.migrateOltp(dataSource(env, "DB_URL", "DB_USER", "DB_PASSWORD")) : 0;
        int dwh = Migrator.migrateDwh(dataSource(env, "WAREHOUSE_URL", "WAREHOUSE_USERNAME", "WAREHOUSE_PASSWORD"));
        StringBuilder report = new StringBuilder();
        warnings.forEach(warning -> report.append("WARN ").append(warning).append('\n'));
        return report.append("migrations applied: scope=")
                .append(scope)
                .append(" oltp=")
                .append(oltp)
                .append(" dwh=")
                .append(dwh)
                .toString();
    }

    /** The environment with every old name also present under its current one, unless that is set already. */
    private static Map<String, String> withCurrentNames(Map<String, String> environment, List<String> warnings) {
        Map<String, String> env = new HashMap<>(environment);
        environment.forEach((name, value) -> {
            Optional<String> current = LegacyConfigNames.environmentVariable(name);
            if (current.isPresent() && !environment.containsKey(current.get())) {
                env.put(current.get(), value);
                warnings.add(LegacyConfigNames.warning(name, current.get()));
            }
        });
        return env;
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
