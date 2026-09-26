package com.greenwhite.dwh.instance.audit;

import com.greenwhite.dwh.instance.support.TestDatabases;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogRepositoryPaginationIntegrationTest {

    private static final Instant TIE_TIMESTAMP = Instant.parse("2026-09-04T10:15:30Z");

    static JdbcClient jdbc;
    static AuditLogRepository repository;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("dwh_audit_pagination_test");
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
}
