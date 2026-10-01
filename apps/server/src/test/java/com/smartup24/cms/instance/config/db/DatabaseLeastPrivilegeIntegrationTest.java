package com.smartup24.cms.instance.config.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-0008: the database roles have the least privileges they need (Database Least Privilege, CWE-250).
 */
@Testcontainers
class DatabaseLeastPrivilegeIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("smartupcms")
            .withUsername("postgres_admin")
            .withPassword("admin_secret");

    static DataSource adminDataSource;
    static DataSource migratorDataSource;
    static DataSource appDataSource;
    static DataSource backupDataSource;

    static JdbcClient adminJdbc;
    static JdbcClient migratorJdbc;
    static JdbcClient appJdbc;
    static JdbcClient backupJdbc;

    static final String MIGRATOR_USER = "smartupcms_migrator";
    static final String MIGRATOR_PASS = "migrator_secret";
    static final String APP_USER = "smartupcms";
    static final String APP_PASS = "app_secret";
    static final String BACKUP_USER = "smartupcms_backup";
    static final String BACKUP_PASS = "backup_secret";

    @BeforeAll
    static void setupRolesAndMigrations() {
        adminDataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        adminJdbc = JdbcClient.create(adminDataSource);

        // 1. The administrator creates the roles and the extensions
        // (postgres_admin)
        bootstrapRoles(adminJdbc);

        migratorDataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), MIGRATOR_USER, MIGRATOR_PASS);
        migratorJdbc = JdbcClient.create(migratorDataSource);

        // 2. smartupcms_migrator (the schema owner) applies the migrations
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(migratorDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // 3. Fine-grained permissions are set up after the tables exist
        grantPrivileges(adminJdbc);

        appDataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), APP_USER, APP_PASS);
        appJdbc = JdbcClient.create(appDataSource);

        backupDataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), BACKUP_USER, BACKUP_PASS);
        backupJdbc = JdbcClient.create(backupDataSource);
    }

    private static void bootstrapRoles(JdbcClient admin) {
        // The administrator creates the extensions
        admin.sql("CREATE EXTENSION IF NOT EXISTS \"pgcrypto\"").update();
        admin.sql("CREATE EXTENSION IF NOT EXISTS \"pg_trgm\"").update();
        admin.sql("CREATE EXTENSION IF NOT EXISTS \"fuzzystrmatch\"").update();

        // The migrator role
        admin.sql("""
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartupcms_migrator') THEN
                    CREATE ROLE smartupcms_migrator LOGIN PASSWORD 'migrator_secret'
                        NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION;
                END IF;
            END $$;
            """).update();

        // The application role
        admin.sql("""
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartupcms') THEN
                    CREATE ROLE smartupcms LOGIN PASSWORD 'app_secret'
                        NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION;
                END IF;
            END $$;
            """).update();

        // The backup role
        admin.sql("""
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartupcms_backup') THEN
                    CREATE ROLE smartupcms_backup LOGIN PASSWORD 'backup_secret'
                        NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION;
                END IF;
            END $$;
            """).update();

        // The database permissions and the ownership of the public schema go to the migrator
        admin.sql("GRANT CONNECT, CREATE ON DATABASE smartupcms TO smartupcms_migrator")
                .update();
        admin.sql("ALTER SCHEMA public OWNER TO smartupcms_migrator").update();
        admin.sql("GRANT ALL ON SCHEMA public TO smartupcms_migrator").update();

        // Default permissions for the objects the migrator
        // will create
        admin.sql("""
            ALTER DEFAULT PRIVILEGES FOR ROLE smartupcms_migrator IN SCHEMA public
                GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO smartupcms;
            ALTER DEFAULT PRIVILEGES FOR ROLE smartupcms_migrator IN SCHEMA public
                GRANT USAGE, SELECT ON SEQUENCES TO smartupcms;
            ALTER DEFAULT PRIVILEGES FOR ROLE smartupcms_migrator IN SCHEMA public
                REVOKE TRUNCATE ON TABLES FROM smartupcms;

            ALTER DEFAULT PRIVILEGES FOR ROLE smartupcms_migrator IN SCHEMA public
                GRANT SELECT ON TABLES TO smartupcms_backup;
            ALTER DEFAULT PRIVILEGES FOR ROLE smartupcms_migrator IN SCHEMA public
                GRANT SELECT ON SEQUENCES TO smartupcms_backup;
            """).update();
    }

    private static void grantPrivileges(JdbcClient admin) {
        admin.sql("""
            GRANT CONNECT ON DATABASE smartupcms TO smartupcms;
            GRANT USAGE ON SCHEMA public TO smartupcms;
            REVOKE CREATE ON SCHEMA public FROM smartupcms;

            GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO smartupcms;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO smartupcms;

            -- Явный отзыв опасных привилегий
            REVOKE TRUNCATE ON ALL TABLES IN SCHEMA public FROM smartupcms;
            REVOKE UPDATE, DELETE, TRUNCATE ON audit_log, audit_log_default FROM smartupcms;

            -- Резервное копирование
            GRANT CONNECT ON DATABASE smartupcms TO smartupcms_backup;
            GRANT USAGE ON SCHEMA public TO smartupcms_backup;
            REVOKE CREATE ON SCHEMA public FROM smartupcms_backup;
            GRANT SELECT ON ALL TABLES IN SCHEMA public TO smartupcms_backup;
            GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO smartupcms_backup;
            ALTER ROLE smartupcms_backup SET default_transaction_read_only = on;
            """).update();
    }

    @Nested
    @DisplayName("Runtime Application User (smartupcms) Least Privilege")
    class AppUserPrivileges {

        @Test
        @DisplayName("Пользователь приложения не суперпользователь и не может создавать роли или базы")
        void appUserIsNotSuperuser() {
            var roleInfo = appJdbc.sql("""
                    select rolsuper, rolcreatedb, rolcreaterole
                    from pg_roles where rolname = 'smartupcms'
                    """)
                    .query((rs, rowNum) -> new boolean[] {
                        rs.getBoolean("rolsuper"), rs.getBoolean("rolcreatedb"), rs.getBoolean("rolcreaterole")
                    })
                    .single();

            assertThat(roleInfo[0]).as("rolsuper must be false").isFalse();
            assertThat(roleInfo[1]).as("rolcreatedb must be false").isFalse();
            assertThat(roleInfo[2]).as("rolcreaterole must be false").isFalse();
        }

        @Test
        @DisplayName("Пользователь приложения не может создавать таблицы (DDL CREATE TABLE запрещен)")
        void appUserCannotCreateTable() {
            assertThatThrownBy(() -> appJdbc.sql("create table probe_least_privilege(id int)")
                            .update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied for schema public");
        }

        @Test
        @DisplayName("Пользователь приложения не может удалять таблицы (DDL DROP TABLE запрещен)")
        void appUserCannotDropTable() {
            assertThatThrownBy(() -> appJdbc.sql("drop table md_users").update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("must be owner of table md_users");
        }

        @Test
        @DisplayName("Пользователь приложения не может менять структуру таблиц (DDL ALTER TABLE запрещен)")
        void appUserCannotAlterTable() {
            assertThatThrownBy(() -> appJdbc.sql("alter table md_users add column attacker_probe text")
                            .update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("must be owner of table md_users");
        }

        @Test
        @DisplayName("Пользователь приложения не может очищать таблицы через TRUNCATE")
        void appUserCannotTruncateTables() {
            assertThatThrownBy(() -> appJdbc.sql("truncate table md_users").update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied for table md_users");

            assertThatThrownBy(() -> appJdbc.sql("truncate table audit_log").update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied for table audit_log");
        }

        @Test
        @DisplayName("Пользователь приложения не может отключить триггеры неизменяемости audit_log")
        void appUserCannotDisableTriggers() {
            assertThatThrownBy(() -> appJdbc.sql("alter table audit_log disable trigger all")
                            .update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("must be owner of table audit_log");
        }

        @Test
        @DisplayName("Пользователь приложения может выполнять обычный DML (SELECT, INSERT, UPDATE, DELETE)")
        void appUserCanPerformStandardDml() {
            long userCount = appJdbc.sql("select count(*) from md_users")
                    .query(Long.class)
                    .single();
            assertThat(userCount).isGreaterThanOrEqualTo(0);

            // INSERT into audit_log is allowed
            appJdbc.sql("""
                    insert into audit_log (table_name, row_pk, event, changed_at)
                    values ('least_privilege_test', '1', 'I', now())
                    """).update();

            long auditCount = appJdbc.sql("select count(*) from audit_log where table_name = 'least_privilege_test'")
                    .query(Long.class)
                    .single();
            assertThat(auditCount).isEqualTo(1);
        }

        @Test
        @DisplayName("AuditPartitionRepository создает и отцепляет партиции без DDL-прав приложения")
        void auditPartitionWorkerCanManagePartitionsViaSecurityDefiner() {
            var repo = new AuditPartitionRepository(appJdbc);

            // Create a partition for a future month
            YearMonth targetMonth = YearMonth.of(2021, 5);
            assertThat(repo.exists(targetMonth)).isFalse();
            repo.create(targetMonth);
            assertThat(repo.exists(targetMonth)).isTrue();

            // Write into the created partition
            appJdbc.sql("""
                    insert into audit_log (table_name, row_pk, event, changed_at)
                    values ('future_partition_test', '100', 'I', timestamptz '2021-05-10 10:00:00+00')
                    """).update();

            // Detach the partition
            String archived = repo.detachAndArchive(targetMonth);
            assertThat(archived).isEqualTo("audit_log_archived_2021_05");

            // The record is kept in the archive table
            long archivedCount = appJdbc.sql(
                            "select count(*) from audit_log_archived_2021_05 where table_name = 'future_partition_test'")
                    .query(Long.class)
                    .single();
            assertThat(archivedCount).isEqualTo(1);
        }

        @Test
        @DisplayName("V127: the application role creates daily partitions and cannot erase the trace of an archive")
        void appUserManagesDailyPartitionsButNotTheArchiveTrace() {
            LocalDate day = LocalDate.of(2021, 6, 7);
            assertThat(new AuditPartitionRepository(appJdbc).createDay(day)).isEqualTo("audit_log_2021_06_07");

            Long id = appJdbc.sql("""
                    insert into audit_log_archives (file_key, storage, period_from, period_to, row_count, byte_size, sha256)
                    values ('least-privilege.jsonl.gz', 'local', timestamptz '2021-06-07 00:00:00+00',
                            timestamptz '2021-06-08 00:00:00+00', 0, 0, 'probe')
                    returning id
                    """).query(Long.class).single();
            appJdbc.sql("update audit_log_archives set verified_at = now() where id = :id")
                    .param("id", id)
                    .update();

            assertThatThrownBy(() -> appJdbc.sql("delete from audit_log_archives where id = :id")
                            .param("id", id)
                            .update())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("permanent");
            assertThatThrownBy(() -> appJdbc.sql("select audit_log_drop_archived_partition('audit_log_2021_06_07')")
                            .query()
                            .singleValue())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("no verified archive");
        }

        @Test
        @DisplayName("SchemaVersionGate успешно проходит проверку схемы под пользователем приложения")
        void schemaVersionGatePassesForAppUser() {
            var gate = new SchemaVersionGate(appDataSource, true);
            gate.verifySchemaMatchesApplication();
        }
    }

    @Nested
    @DisplayName("Backup User (smartupcms_backup) Read-Only Privilege")
    class BackupUserPrivileges {

        @Test
        @DisplayName("Пользователь бэкапа может читать все таблицы")
        void backupUserCanReadTables() {
            long count = backupJdbc
                    .sql("select count(*) from md_users")
                    .query(Long.class)
                    .single();
            assertThat(count).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Пользователь бэкапа не может выполнять операции записи")
        void backupUserCannotWrite() {
            assertThatThrownBy(() -> backupJdbc.sql("""
                    insert into audit_log (table_name, row_pk, event, changed_at)
                    values ('backup_write_probe', '1', 'I', now())
                    """).update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("read-only transaction");
        }
    }
}
