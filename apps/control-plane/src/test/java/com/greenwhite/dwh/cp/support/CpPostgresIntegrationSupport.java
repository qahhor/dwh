package com.greenwhite.dwh.cp.support;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

public abstract class CpPostgresIntegrationSupport {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine");
    private static final DataSource DATA_SOURCE;
    private static final JdbcClient JDBC;

    static {
        POSTGRES.start();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        DATA_SOURCE = dataSource;
        JDBC = JdbcClient.create(DATA_SOURCE);
    }

    protected static DataSource dataSource() {
        return DATA_SOURCE;
    }

    protected static JdbcClient jdbc() {
        return JDBC;
    }

    protected static void cleanAndMigrateTo(String version) {
        Flyway flyway = Flyway.configure()
                .dataSource(DATA_SOURCE)
                .cleanDisabled(false)
                .target(MigrationVersion.fromVersion(version))
                .load();
        flyway.clean();
        flyway.migrate();
    }

    protected static void migrateLatest() {
        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .load()
                .migrate();
    }

    protected boolean tableExists(String tableName) {
        return jdbc().sql("""
                        select exists (
                            select 1
                            from information_schema.tables
                            where table_schema = 'public' and table_name = :tableName
                        )
                        """)
                .param("tableName", tableName)
                .query(Boolean.class)
                .single();
    }
}
