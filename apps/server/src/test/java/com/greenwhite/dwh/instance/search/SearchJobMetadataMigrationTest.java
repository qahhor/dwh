package com.greenwhite.dwh.instance.search;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class SearchJobMetadataMigrationTest extends SearchSettingsIntegrationTestSupport {
    @Test void migrationPreservesHistoricalUnknownRequestSemanticsAndMarksNewRequests() {
        String schema = "task2_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var previous = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("26").load();
        previous.migrate();
        UUID oldId = UUID.randomUUID();
        jdbc.sql("insert into " + schema + ".search_jobs(id,request_id,action,state) values(:id,:request,'CHECK','FAILED')")
                .param("id", oldId).param("request", UUID.randomUUID()).update();
        var upgrade = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load();
        upgrade.migrate();
        assertThat(jdbc.sql("select count(*) from information_schema.columns where table_schema=:schema and table_name='search_jobs' and column_name='request_metadata_recorded'")
                .param("schema", schema).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("select request_metadata_recorded from " + schema + ".search_jobs where id=:id")
                .param("id", oldId).query(Boolean.class).single()).isFalse();
        UUID newId = UUID.randomUUID();
        jdbc.sql("insert into " + schema + ".search_jobs(id,request_id,action,state,retry_of_job_id) values(:id,:request,'CHECK','QUEUED',:old)")
                .param("id", newId).param("request", UUID.randomUUID()).param("old", oldId).update();
        assertThat(jdbc.sql("select request_metadata_recorded from " + schema + ".search_jobs where id=:id")
                .param("id", newId).query(Boolean.class).single()).isTrue();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }
}
