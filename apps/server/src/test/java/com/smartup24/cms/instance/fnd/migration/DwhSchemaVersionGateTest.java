package com.smartup24.cms.instance.fnd.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartup24.cms.instance.support.TestDatabases;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The gate of the second database {@code pg-dwh}. The framework gate ({@code config.db.SchemaVersionGate}) checks
 * the OLTP schema, so it is not duplicated here.
 *
 * <p>An architect's assumption: the check calls {@code afterPropertiesSet()} directly instead of starting a
 * context, because the framework has no {@code @SpringBootTest} at all: a full context pulls in external services
 * (search, mail, storage). The {@link Component} annotation puts the gate into the context, and a separate
 * assertion checks it.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class DwhSchemaVersionGateTest {

    private static final String DWH_EMPTY = "gate_dwh_empty";
    private static final String DWH_FAILED = "gate_dwh_failed";

    @BeforeAll
    static void prepareDatabases() {
        TestDatabases.migrateOnce();
        // pg-dwh with no migrations at all
        TestDatabases.createDatabase(DWH_EMPTY);
        // pg-dwh whose last migration is marked as failed
        TestDatabases.createDatabase(DWH_FAILED);
        FndMigrator.migrateDwh(TestDatabases.database(DWH_FAILED));
        JdbcClient.create(TestDatabases.database(DWH_FAILED))
                .sql("update flyway_schema_history set success = false "
                        + "where installed_rank = (select max(installed_rank) from flyway_schema_history)")
                .update();
    }

    @Test
    @DisplayName("AC-4: миграции pg-dwh не применялись — SchemaVersionMismatchException, код выхода 3, событие в логе")
    void emptyDwhStopsStartup(CapturedOutput output) {
        DwhSchemaVersionGate gate = new DwhSchemaVersionGate(TestDatabases.database(DWH_EMPTY));

        Throwable failure = catchThrowable(gate::afterPropertiesSet);

        assertThat(failure).isInstanceOf(SchemaVersionMismatchException.class);
        SchemaVersionMismatchException mismatch = (SchemaVersionMismatchException) failure;
        assertThat(mismatch.db()).isEqualTo("dwh");
        assertThat(mismatch.actual()).isEqualTo("none");
        assertThat(((ExitCodeGenerator) mismatch).getExitCode()).isEqualTo(3);
        assertThat(output.getOut())
                .contains(DwhSchemaVersionGate.EVENT)
                .contains("db=dwh")
                .contains("actual=none");
    }

    @Test
    @DisplayName("AC-4: миграция pg-dwh помечена success=false — старт прерван с actual=failed:<версия>")
    void failedMigrationStopsStartup() {
        DwhSchemaVersionGate gate = new DwhSchemaVersionGate(TestDatabases.database(DWH_FAILED));

        Throwable failure = catchThrowable(gate::afterPropertiesSet);

        assertThat(failure).isInstanceOf(SchemaVersionMismatchException.class);
        assertThat(((SchemaVersionMismatchException) failure).actual()).startsWith("failed:");
    }

    @Test
    @DisplayName("AC-4: база на ожидаемой версии — gate пропускает и ничего не мигрирует")
    void matchingVersionPasses() {
        long before = appliedMigrations(TestDatabases.DWH_DB);

        new DwhSchemaVersionGate(TestDatabases.dwh()).afterPropertiesSet();

        assertThat(appliedMigrations(TestDatabases.DWH_DB)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-4: gate объявлен бином — попадает в контекст приложения")
    void gateIsSpringComponent() {
        assertThat(DwhSchemaVersionGate.class.getAnnotation(Component.class)).isNotNull();
    }

    private static long appliedMigrations(String database) {
        return JdbcClient.create(TestDatabases.database(database))
                .sql("select count(*) from flyway_schema_history")
                .query(Long.class)
                .single();
    }
}
