package com.greenwhite.dwh.instance.audit;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.AuditLogFilters;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.AuditRecord;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.SecurityEventFilters;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository.SecurityEventRecord;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.audit.service.AuditListExporters;
import com.greenwhite.dwh.instance.audit.service.AuditListService;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.audit.service.AuditQuery;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.query.QueryCompiler;
import com.greenwhite.dwh.instance.common.query.QueryListRepository;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The audit log and the security events on the field registry (ADR-0016, roadmap item 50). */
@Testcontainers
class AuditListIntegrationTest {

    private static final Instant TIE = Instant.parse("2026-09-04T10:15:30Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_audit_list_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static AuditListService audit;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        var repository = new AuditLogRepository(jdbc, new ObjectMapper());
        audit = new AuditListService(new QueryListRepository(jdbc), repository,
                new AuditLogService(repository, null, new AuditDataRedactor()));
    }

    @Test
    @DisplayName("Audit rows: newest first, ties broken by id without duplicates, the total counted once")
    void auditRowsNewestFirstThroughTies() {
        long oldest = insertAudit("al_tie", "1", "U", TIE, "{}");
        long middle = insertAudit("al_tie", "2", "U", TIE, "{}");
        long newest = insertAudit("al_tie", "3", "U", TIE, "{}");
        var filters = new AuditLogFilters("al_tie", null, null, null, null, null);

        var first = audit.logs(2, null, null, null, null, filters);
        var second = audit.logs(2, first.nextCursor(), null, null, null, filters);

        assertThat(first.items()).extracting(AuditRecord::id).containsExactly(newest, middle);
        assertThat(second.items()).extracting(AuditRecord::id).containsExactly(oldest);
        assertThat(first.totalEstimated()).isEqualTo(3);
    }

    @Test
    @DisplayName("The DSL narrows audit rows like the flat filters, and a cursor belongs to its filters")
    void dslAndCursorOnAuditRows() {
        insertAudit("al_dsl", "1", "I", TIE, "{}");
        insertAudit("al_dsl", "2", "D", TIE.plusSeconds(1), "{}");
        var table = new AuditLogFilters("al_dsl", null, null, null, null, null);

        assertThat(audit.logs(null, null, "[{\"field\":\"event\",\"op\":\"eq\",\"value\":\"D\"}]", null, null, table)
                .items()).extracting(AuditRecord::rowPk).containsExactly("2");
        assertThat(audit.logs(null, null, null, "changedAt", null, table).items())
                .extracting(AuditRecord::rowPk).containsExactly("1", "2");

        var first = audit.logs(1, null, null, null, null, table);
        assertThatThrownBy(() -> audit.logs(1, first.nextCursor(), null, null, null,
                new AuditLogFilters("al_dsl", null, "I", null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getFieldErrors())
                        .extracting(FieldErrorItem::code).containsExactly(QueryCompiler.INVALID_CURSOR));
    }

    @Test
    @DisplayName("Only the event time sorts: a large partitioned log is never sorted by an unindexed column")
    void onlyTheEventTimeSorts() {
        assertThatThrownBy(() -> audit.logs(null, null, null, "tableName", null, AuditLogFilters.none()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getFieldErrors())
                        .extracting(FieldErrorItem::code).containsExactly(QueryCompiler.SORT_INVALID));
    }

    @Test
    @DisplayName("Credentials in old and new rows and in event details are masked on every page")
    void pagesAreRedacted() {
        insertAudit("al_secret", "1", "U", TIE, "{\"password_hash\":\"secret-hash\",\"state\":\"A\"}");
        insertSecurityEvent("AL_SECRET", "10.0.0.7", "{\"token\":\"live-token\",\"reason\":\"x\"}", TIE);

        var row = audit.logs(null, null, null, null, null, new AuditLogFilters("al_secret", null, null, null, null, null))
                .items().getFirst();
        assertThat(row.newRow()).containsEntry("password_hash", "[REDACTED]").containsEntry("state", "A");

        var event = audit.securityEvents(null, null, null, null, null,
                new SecurityEventFilters("AL_SECRET", null, null, null, null)).items().getFirst();
        assertThat(event.details()).containsEntry("token", "[REDACTED]").containsEntry("reason", "x");
    }

    @Test
    @DisplayName("Security events: newest first through ties, search q in the address, flat filters kept")
    void securityEventsThroughTiesAndSearch() {
        long oldest = insertSecurityEvent("AL_TIE", "10.1.1.1", "{}", TIE);
        long middle = insertSecurityEvent("AL_TIE", "10.1.1.2", "{}", TIE);
        long newest = insertSecurityEvent("AL_TIE", "10.9.9.9", "{}", TIE);
        var type = new SecurityEventFilters("AL_TIE", null, null, null, null);

        var first = audit.securityEvents(2, null, null, null, null, type);
        var second = audit.securityEvents(2, first.nextCursor(), null, null, null, type);
        assertThat(first.items()).extracting(SecurityEventRecord::id).containsExactly(newest, middle);
        assertThat(second.items()).extracting(SecurityEventRecord::id).containsExactly(oldest);

        assertThat(audit.securityEvents(null, null, null, null, "10.9.9", type).items())
                .extracting(SecurityEventRecord::id).containsExactly(newest);
        assertThat(audit.securityEvents(null, null, null, null, null,
                new SecurityEventFilters("AL_TIE", null, "10.1.1", null, null)).items())
                .extracting(SecurityEventRecord::id).containsExactly(middle, oldest);
    }

    @Test
    @DisplayName("Every registry field is a property of the row the client receives")
    void everyFieldIsARowProperty() {
        assertThat(AuditQuery.LOGS.fields()).allSatisfy(field -> assertThat(properties(AuditRecord.class)).contains(field.key()));
        assertThat(AuditQuery.SECURITY_EVENTS.fields())
                .allSatisfy(field -> assertThat(properties(SecurityEventRecord.class)).contains(field.key()));
    }

    @Test
    @DisplayName("The exports refuse bad option values before the job starts")
    void exportsCheckOptionValues() {
        var exporters = new AuditListExporters();
        assertThat(exporters.auditLogsExporter(audit).checkOptions(Map.of("event", "U", "user_id", "4",
                "from", "2026-09-01T00:00:00Z"))).isEmpty();
        assertThat(exporters.auditLogsExporter(audit).checkOptions(Map.of("event", "X", "user_id", "me",
                "to", "yesterday"))).extracting(FieldErrorItem::field).containsExactlyInAnyOrder("event", "user_id", "to");
        assertThat(exporters.auditSecurityEventsExporter(audit).checkOptions(Map.of("from", "soon")))
                .extracting(FieldErrorItem::field).containsExactly("from");
    }

    private static List<String> properties(Class<? extends Record> type) {
        return java.util.Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
    }

    private static long insertAudit(String table, String rowPk, String event, Instant at, String newRow) {
        return jdbc.sql("""
                        insert into audit_log (table_name, row_pk, event, changed_at, changed_columns, new_row)
                        values (:table, :rowPk, :event, :at, array['state'], cast(:newRow as jsonb))
                        returning id
                        """)
                .param("table", table).param("rowPk", rowPk).param("event", event)
                .param("at", java.sql.Timestamp.from(at)).param("newRow", newRow)
                .query(Long.class).single();
    }

    private static long insertSecurityEvent(String type, String ip, String details, Instant at) {
        return jdbc.sql("""
                        insert into security_events (event_type, ip, details, created_at)
                        values (:type, cast(:ip as inet), cast(:details as jsonb), :at)
                        returning id
                        """)
                .param("type", type).param("ip", ip).param("details", details).param("at", java.sql.Timestamp.from(at))
                .query(Long.class).single();
    }
}
