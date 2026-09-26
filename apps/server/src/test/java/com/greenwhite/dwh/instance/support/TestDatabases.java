package com.greenwhite.dwh.instance.support;

import com.greenwhite.dwh.instance.fnd.FndActors;
import com.greenwhite.dwh.instance.fnd.migration.FndMigrator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * Один встроенный PostgreSQL на всю сборку и две базы в нём (решение архитектора 06.09, DoD AC-1):
 * {@code postgres} — OLTP, {@code dwh} — pg-dwh. Миграции применяются программно один раз;
 * автомиграция при старте контекста выключена (02 п.10).
 */
public final class TestDatabases {

    public static final String OLTP_DB = "postgres";
    public static final String DWH_DB = "dwh";
    public static final String USER = "postgres";

    private static EmbeddedPostgres postgres;
    private static final Set<String> created = new HashSet<>();
    private static boolean migrated;

    private TestDatabases() {
    }

    public static synchronized EmbeddedPostgres instance() {
        if (postgres == null) {
            try {
                // Test data is thrown away with the process: no durability, so no fsync on every commit (slow on Windows).
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

    /** Создаёт базу с именем {@code name}, если её ещё нет (для сценариев со свежей схемой). */
    public static synchronized void createDatabase(String name) {
        instance();
        if (created.contains(name)) {
            return;
        }
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("create database " + name);
            created.add(name);
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось создать базу " + name, e);
        }
    }

    /** Применяет миграции обеих БД один раз на сборку. */
    public static synchronized void migrateOnce() {
        instance();
        if (!migrated) {
            FndMigrator.migrateOltp(oltp());
            // Контексты тестов поднимаются без параметров первого администратора: непустая md_users
            // оставляет InstanceBootstrap каркаса no-op (раньше это давал сид миграции, теперь — код основы).
            FndActors.ensureSystemUser(JdbcClient.create(oltp()));
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
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("create database " + name + " template " + TEMPLATE_DB);
            created.add(name);
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось создать копию шаблонной базы " + name, e);
        }
        // A small pool: on Windows every new connection to the embedded server starts a process, so a
        // connection per statement (DriverManagerDataSource) made statement-heavy tests several times slower.
        var pool = new com.zaxxer.hikari.HikariConfig();
        pool.setJdbcUrl(jdbcUrl(name));
        pool.setUsername(USER);
        pool.setMaximumPoolSize(4);
        pool.setMinimumIdle(0);
        pool.setIdleTimeout(10_000);
        pool.setPoolName(name);
        return new com.zaxxer.hikari.HikariDataSource(pool);
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
