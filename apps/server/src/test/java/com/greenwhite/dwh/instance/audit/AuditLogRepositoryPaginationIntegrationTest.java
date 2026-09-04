package com.greenwhite.dwh.instance.audit;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AuditLogRepositoryPaginationIntegrationTest {

    private static final Instant TIE_TIMESTAMP = Instant.parse("2026-09-04T10:15:30Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_audit_pagination_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static AuditLogRepository repository;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        repository = new AuditLogRepository(jdbc, new ObjectMapper());
    }

    @Test
    void traversesAuditRowsWithoutDuplicatesWhenTimestampsTie() {
        long oldestId = insertAudit("pagination_probe", "1");
        long middleId = insertAudit("pagination_probe", "2");
        long newestId = insertAudit("pagination_probe", "3");

        var first = repository.listAuditLogs(
                "pagination_probe", null, null, null, null, null, null, null, 2);
        var second = repository.listAuditLogs(
                "pagination_probe", null, null, null, null, null, TIE_TIMESTAMP, middleId, 2);

        assertThat(first).extracting(AuditLogRepository.AuditRecord::id)
                .containsExactly(newestId, middleId);
        assertThat(second).extracting(AuditLogRepository.AuditRecord::id)
                .containsExactly(oldestId);
        assertThat(repository.countAuditLogs("pagination_probe", null, null, null, null, null))
                .isEqualTo(3L);
    }

    @Test
    void traversesSecurityEventsWithoutDuplicatesWhenTimestampsTie() {
        long oldestId = insertSecurityEvent("PAGINATION_PROBE");
        long middleId = insertSecurityEvent("PAGINATION_PROBE");
        long newestId = insertSecurityEvent("PAGINATION_PROBE");

        var first = repository.listSecurityEvents(
                "PAGINATION_PROBE", null, null, null, null, null, null, 2);
        var second = repository.listSecurityEvents(
                "PAGINATION_PROBE", null, null, null, null, TIE_TIMESTAMP, middleId, 2);

        assertThat(first).extracting(AuditLogRepository.SecurityEventRecord::id)
                .containsExactly(newestId, middleId);
        assertThat(second).extracting(AuditLogRepository.SecurityEventRecord::id)
                .containsExactly(oldestId);
        assertThat(repository.countSecurityEvents("PAGINATION_PROBE", null, null, null, null))
                .isEqualTo(3L);
    }

    private static long insertAudit(String tableName, String rowPk) {
        return jdbc.sql("""
                        insert into audit_log (table_name, row_pk, event, changed_at, changed_columns)
                        values (:tableName, :rowPk, 'U', :changedAt, array['state'])
                        returning id
                        """)
                .param("tableName", tableName)
                .param("rowPk", rowPk)
                .param("changedAt", java.sql.Timestamp.from(TIE_TIMESTAMP))
                .query(Long.class).single();
    }

    private static long insertSecurityEvent(String eventType) {
        return jdbc.sql("""
                        insert into security_events (event_type, ip, details, created_at)
                        values (:eventType, '127.0.0.1', '{}'::jsonb, :createdAt)
                        returning id
                        """)
                .param("eventType", eventType)
                .param("createdAt", java.sql.Timestamp.from(TIE_TIMESTAMP))
                .query(Long.class).single();
    }
}
