package com.smartup24.cms.instance.support;

import com.smartup24.cms.instance.fnd.migration.FndMigrator;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One embedded PostgreSQL for the whole build with two databases in it (the architect's decision of 06.09):
 * {@code postgres} for OLTP and {@code dwh} for pg-dwh. Migrations are applied programmatically once;
 * automatic migration at context start is off.
 */
public final class TestDatabases {

    public static final String OLTP_DB = "postgres";
    public static final String DWH_DB = "dwh";
    public static final String USER = "postgres";

    private static EmbeddedPostgres postgres;
    private static final Set<String> created = new HashSet<>();
    private static boolean migrated;

    private TestDatabases() {}

    public static synchronized EmbeddedPostgres instance() {
        if (postgres == null) {
            try {
                // Test data is thrown away with the process: no durability, so no fsync on every commit (slow on
                // Windows).
                postgres = EmbeddedPostgres.builder()
                        .setServerConfig("timezone", "UTC")
                        .setServerConfig("fsync", "off")
                        .setServerConfig("synchronous_commit", "off")
                        .setServerConfig("full_page_writes", "off")
                        .start();
            } catch (IOException e) {
                throw new UncheckedIOException("Встроенный PostgreSQL не запустился", e);
            }
            createDatabase(DWH_DB);
        }
        return postgres;
    }

    /** Creates a database named {@code name} if it does not exist yet (for scenarios with a fresh schema). */
    public static synchronized void createDatabase(String name) {
        instance();
        if (created.contains(name)) {
            return;
        }
        try (Connection c = postgres.getPostgresDatabase().getConnection();
                Statement st = c.createStatement()) {
            st.execute("create database " + name);
            created.add(name);
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось создать базу " + name, e);
        }
    }

    /** Applies the migrations of both databases once per build. */
    public static synchronized void migrateOnce() {
        instance();
        if (!migrated) {
            FndMigrator.migrateOltp(oltp());
            // Test contexts start without the first administrator's parameters: a non-empty md_users keeps the
            // framework's InstanceBootstrap a no-op (a migration seed used to do this; now the foundation code does).
            MdAuditActors.ensureSystemUser(JdbcClient.create(oltp()));
            FndMigrator.migrateDwh(dwh());
            migrated = true;
        }
    }

    private static final String TEMPLATE_DB = "cms_template";
    private static boolean templateReady;
    private static int copies;

    /**
     * A fresh database with every OLTP migration applied, for a test class of its own. The migrations run once
     * into a template; each call copies it ({@code create database … template}), which takes a fraction of a
     * second instead of a container start and 55 migrations per class.
     *
     * @param prefix a readable part of the database name, e.g. {@code users}
     */
    public static synchronized DataSource migratedCopy(String prefix) {
        instance();
        if (!templateReady) {
            createDatabase(TEMPLATE_DB);
            FndMigrator.migrateOltp(database(TEMPLATE_DB));
            templateReady = true;
        }
        String name = prefix.toLowerCase().replaceAll("[^a-z0-9_]", "_") + "_" + (++copies);
        try (Connection c = postgres.getPostgresDatabase().getConnection();
                Statement st = c.createStatement()) {
            st.execute("create database " + name + " template " + TEMPLATE_DB);
            created.add(name);
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось создать копию шаблонной базы " + name, e);
        }
        // A small pool: on Windows every new connection to the embedded server starts a process, so a
        // connection per statement (DriverManagerDataSource) made statement-heavy tests several times slower.
        return pooled(jdbcUrl(name), USER, null, 4, name);
    }

    /**
     * A small connection pool for a test database, instead of {@code DriverManagerDataSource}. A physical connection
     * per transaction leaves a socket in TIME_WAIT each time; a full build on Windows opened enough of them to
     * exhaust the ephemeral ports ({@code BindException: Address already in use}). The pool also keeps sessions alive
     * as the production pool does, so session-owned objects (TEMP tables) must be dropped by the code, not by a
     * disconnect. Connections open on demand and close after ten idle seconds; close the pool with its database.
     */
    public static HikariDataSource pooled(
            String jdbcUrl, String user, @Nullable String password, int maximumSize, String name) {
        var pool = new HikariConfig();
        pool.setJdbcUrl(jdbcUrl);
        pool.setUsername(user);
        if (password != null) pool.setPassword(password);
        pool.setMaximumPoolSize(maximumSize);
        pool.setMinimumIdle(0);
        pool.setIdleTimeout(10_000);
        pool.setConnectionTimeout(10_000);
        pool.setPoolName(name);
        return new HikariDataSource(pool);
    }

    public static DataSource oltp() {
        return instance().getDatabase(USER, OLTP_DB);
    }

    public static DataSource dwh() {
        return instance().getDatabase(USER, DWH_DB);
    }

    public static DataSource database(String name) {
        return instance().getDatabase(USER, name);
    }

    public static String jdbcUrl(String database) {
        return instance().getJdbcUrl(USER, database);
    }
}
