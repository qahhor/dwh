package com.smartup24.cms.instance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.config.db.SchemaVersionGate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SearchIndexManagementMigrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void upgradePreservesBusinessRowsAndEnforcesSearchManagementContracts() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("025")
                .load()
                .migrate();
        var jdbc = JdbcClient.create(ds);

        long userId = jdbc.sql("""
                insert into md_users(name,login,email)
                values ('Preserved','search-migration','search-migration@example.test')
                returning id
                """).query(Long.class).single();
        long projectId = jdbc.sql("""
                insert into ms_task_projects(name,description,created_by)
                values ('Preserved project','Business description',:userId)
                returning id
                """).param("userId", userId).query(Long.class).single();
        long taskId = jdbc.sql("""
                insert into ms_tasks(title,project_id,reporter_id,status_id)
                select 'Preserved task',:projectId,:userId,id
                from ms_task_statuses where pcode='new'
                returning id
                """)
                .param("projectId", projectId)
                .param("userId", userId)
                .query(Long.class)
                .single();
        jdbc.sql("""
                insert into audit_log(table_name,row_pk,event,changed_by,new_row)
                values ('ms_tasks',:taskId,'I',:userId,'{"title":"Preserved task"}'::jsonb)
                """)
                .param("taskId", Long.toString(taskId))
                .param("userId", userId)
                .update();

        var businessTables = List.of("md_users", "ms_task_projects", "ms_tasks", "audit_log");
        var before = businessTables.stream().map(table -> snapshot(jdbc, table)).toList();
        assertThatThrownBy(() -> new SchemaVersionGate(ds, true).verifySchemaMatchesApplication())
                .isInstanceOf(IllegalStateException.class);

        var flyway = FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(2); // V026 plus forward-only migrations.

        assertThat(jdbc.sql("""
                select count(*) from information_schema.tables
                where table_schema='public' and table_name in
                ('search_projection_versions','search_generations','search_generation_delivery',
                 'search_index_state','search_jobs','search_settings')
                """).query(Long.class).single()).isEqualTo(6L);
        assertThat(businessTables.stream().map(table -> snapshot(jdbc, table)).toList())
                .isEqualTo(before);
        assertThat(jdbc.sql("select initialized from search_index_state where id=1")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbc.sql("select active_generation_id is null from search_index_state where id=1")
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(jdbc.sql("select configuration::text from search_settings where id=1")
                        .query(String.class)
                        .single())
                .isEqualTo("{}");

        assertThatThrownBy(() -> jdbc.sql("""
                insert into search_projection_versions(entity_type,entity_id,revision)
                values ('FILE',1,1)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                insert into search_projection_versions(entity_type,entity_id,revision)
                values ('TASK',0,1)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                insert into search_projection_versions(entity_type,entity_id,revision)
                values ('TASK',1,0)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.sql("""
                insert into search_projection_versions(entity_type,entity_id,revision)
                values ('TASK',:taskId,1),('PROJECT',:projectId,1),('USER',:userId,1)
                """)
                .param("taskId", taskId)
                .param("projectId", projectId)
                .param("userId", userId)
                .update();
        UUID generationId = UUID.randomUUID();
        jdbc.sql("""
                insert into search_generations(
                  id,state,task_collection,project_collection,user_collection,
                  schema_version,schema_profile,settings_version)
                values (:id,'BUILDING','tasks_v1','projects_v1','users_v1',0,'MIXED',1)
                """).param("id", generationId).update();
        jdbc.sql("""
                insert into search_generation_delivery(generation_id,entity_type,entity_id)
                values (:generationId,'TASK',:taskId)
                """)
                .param("generationId", generationId)
                .param("taskId", taskId)
                .update();

        jdbc.sql("""
                insert into search_jobs(id,request_id,action,generation_id,state)
                values (:id,:requestId,'REBUILD',:generationId,'QUEUED')
                """)
                .param("id", UUID.randomUUID())
                .param("requestId", UUID.randomUUID())
                .param("generationId", generationId)
                .update();
        assertThatThrownBy(() -> jdbc.sql("""
                insert into search_jobs(id,request_id,action,generation_id,state)
                values (:id,:requestId,'ROLLBACK',:generationId,'RUNNING')
                """)
                        .param("id", UUID.randomUUID())
                        .param("requestId", UUID.randomUUID())
                        .param("generationId", generationId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("""
                insert into search_jobs(id,request_id,action,generation_id,state)
                values (:id,:requestId,'ROLLBACK',:generationId,'SUCCEEDED')
                """)
                .param("id", UUID.randomUUID())
                .param("requestId", UUID.randomUUID())
                .param("generationId", generationId)
                .update();
        jdbc.sql("""
                insert into search_jobs(id,request_id,action,generation_id,state)
                values (:id,:requestId,'CHECK',:generationId,'QUEUED')
                """)
                .param("id", UUID.randomUUID())
                .param("requestId", UUID.randomUUID())
                .param("generationId", generationId)
                .update();

        assertThat(jdbc.sql("select count(*) from search_generation_delivery")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("select count(*) from search_jobs")
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        new SchemaVersionGate(ds, true).verifySchemaMatchesApplication();
    }

    @Test
    void emptyDatabaseMigratesLatestAndBecomesReady() {
        try (var empty = new PostgreSQLContainer("postgres:18-alpine")) {
            empty.start();
            var ds = new DriverManagerDataSource(empty.getJdbcUrl(), empty.getUsername(), empty.getPassword());
            assertThat(FlywayUtcConfiguration.configure(Flyway.configure())
                            .dataSource(ds)
                            .locations("classpath:db/migration")
                            .load()
                            .migrate()
                            .success)
                    .isTrue();
            new SchemaVersionGate(ds, true).verifySchemaMatchesApplication();
        }
    }

    /**
     * The business values of a table: every column but the revision, and for the tasks and projects the columns that
     * outlive the move of both to the general runtime (ADR-0032, 8), which replaces their status and state columns.
     */
    private static List<String> snapshot(JdbcClient jdbc, String table) {
        List<String> kept = KEPT_COLUMNS.get(table);
        String row = kept == null
                ? "to_jsonb(t) - 'revision'"
                : "(select jsonb_object_agg(e.key, e.value) from jsonb_each(to_jsonb(t)) e where e.key in ('"
                        + String.join("','", kept) + "'))";
        return jdbc.sql("select (" + row + ")::text from " + table + " t order by id")
                .query(String.class)
                .list();
    }

    private static final Map<String, List<String>> KEPT_COLUMNS = Map.of(
            "ms_task_projects",
            List.of("id", "name", "description", "attributes", "created_at", "created_by"),
            "ms_tasks",
            List.of(
                    "id",
                    "title",
                    "description_markdown",
                    "priority",
                    "project_id",
                    "parent_task_id",
                    "reporter_id",
                    "attributes",
                    "begin_time",
                    "end_time",
                    "created_at"));
}
