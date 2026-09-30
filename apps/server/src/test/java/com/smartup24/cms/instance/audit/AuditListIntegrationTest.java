package com.smartup24.cms.instance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.api.AuditLogFilter;
import com.smartup24.cms.instance.audit.api.AuditLogView;
import com.smartup24.cms.instance.audit.api.SecurityEventFilter;
import com.smartup24.cms.instance.audit.api.SecurityEventView;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditListExporters;
import com.smartup24.cms.instance.audit.service.AuditListService;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.audit.service.AuditQuery;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.support.TestDatabases;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** The audit log and the security events on the field registry (ADR-0016, roadmap item 50). */
class AuditListIntegrationTest {

    private static final Instant TIE = Instant.parse("2026-09-04T10:15:30Z");

    static JdbcClient jdbc;
    static AuditListService audit;
    static AuditListService counting;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("dwh_audit_list_test");
        jdbc = JdbcClient.create(ds);
        var repository = new AuditLogRepository(jdbc, new ObjectMapper());
        audit = new AuditListService(
                QueryListRepository.estimatingFrom(jdbc, 0),
                repository,
                new AuditLogService(repository, null, new AuditDataRedactor()));
        counting = new AuditListService(
                new QueryListRepository(jdbc),
                repository,
                new AuditLogService(repository, null, new AuditDataRedactor()));
    }

    @Test
    @DisplayName("Below the estimate threshold the first page counts the rows: a small log is never shown as ≈ N")
    void smallLogIsCountedExactly() {
        insertAudit("al_small", "1", "U", TIE, "{}");
        insertAudit("al_small", "2", "U", TIE, "{}");
        insertAudit("al_small", "3", "U", TIE, "{}");
        var filters = new AuditLogFilter("al_small", null, null, null, null, null);

        var first = counting.logs(2, null, null, null, null, filters);

        assertThat(first.hasMore()).isTrue();
        assertThat(first.totalExact()).isTrue();
        assertThat(first.totalEstimated()).isEqualTo(3);
    }

    @Test
    @DisplayName("Audit rows: newest first, ties broken by id without duplicates, the total counted once")
    void auditRowsNewestFirstThroughTies() {
        long oldest = insertAudit("al_tie", "1", "U", TIE, "{}");
        long middle = insertAudit("al_tie", "2", "U", TIE, "{}");
        long newest = insertAudit("al_tie", "3", "U", TIE, "{}");
        var filters = new AuditLogFilter("al_tie", null, null, null, null, null);

        var first = audit.logs(2, null, null, null, null, filters);
        var second = audit.logs(2, first.nextCursor(), null, null, null, filters);

        assertThat(first.items()).extracting(AuditLogView::id).containsExactly(newest, middle);
        assertThat(second.items()).extracting(AuditLogView::id).containsExactly(oldest);
        // The log grows without bound (plan item 3.5): a first page with more after it reports the planner's
        // estimate, never less than the rows it has seen; a page that holds the whole result counts it exactly.
        assertThat(first.totalExact()).isFalse();
        assertThat(first.totalEstimated()).isGreaterThanOrEqualTo(3);
        var whole = audit.logs(10, null, null, null, null, filters);
        assertThat(whole.totalExact()).isTrue();
        assertThat(whole.totalEstimated()).isEqualTo(3);
    }

    @Test
    @DisplayName("The DSL narrows audit rows like the flat filters, and a cursor belongs to its filters")
    void dslAndCursorOnAuditRows() {
        insertAudit("al_dsl", "1", "I", TIE, "{}");
        insertAudit("al_dsl", "2", "D", TIE.plusSeconds(1), "{}");
        var table = new AuditLogFilter("al_dsl", null, null, null, null, null);

        assertThat(audit.logs(null, null, "[{\"field\":\"event\",\"op\":\"eq\",\"value\":\"D\"}]", null, null, table)
                        .items())
                .extracting(AuditLogView::rowPk)
                .containsExactly("2");
        assertThat(audit.logs(null, null, null, "changedAt", null, table).items())
                .extracting(AuditLogView::rowPk)
                .containsExactly("1", "2");

        var first = audit.logs(1, null, null, null, null, table);
        assertThatThrownBy(() -> audit.logs(
                        1,
                        first.nextCursor(),
                        null,
                        null,
                        null,
                        new AuditLogFilter("al_dsl", null, "I", null, null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .extracting(FieldErrorItem::code)
                                .containsExactly(QueryCompiler.INVALID_CURSOR));
    }

    @Test
    @DisplayName("Only the event time sorts: a large partitioned log is never sorted by an unindexed column")
    void onlyTheEventTimeSorts() {
        assertThatThrownBy(() -> audit.logs(null, null, null, "tableName", null, AuditLogFilter.none()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .extracting(FieldErrorItem::code)
                                .containsExactly(QueryCompiler.SORT_INVALID));
    }

    @Test
    @DisplayName("Credentials in old and new rows and in event details are masked on every page")
    void pagesAreRedacted() {
        insertAudit("al_secret", "1", "U", TIE, "{\"password_hash\":\"secret-hash\",\"state\":\"A\"}");
        insertSecurityEvent("AL_SECRET", "10.0.0.7", "{\"token\":\"live-token\",\"reason\":\"x\"}", TIE);

        var row = audit.logs(
                        null, null, null, null, null, new AuditLogFilter("al_secret", null, null, null, null, null))
                .items()
                .getFirst();
        assertThat(row.newRow()).containsEntry("password_hash", "[REDACTED]").containsEntry("state", "A");

        var event = audit.securityEvents(
                        null, null, null, null, null, new SecurityEventFilter("AL_SECRET", null, null, null, null))
                .items()
                .getFirst();
        assertThat(event.details()).containsEntry("token", "[REDACTED]").containsEntry("reason", "x");
    }

    @Test
    @DisplayName("Security events: newest first through ties, search q in the address, flat filters kept")
    void securityEventsThroughTiesAndSearch() {
        long oldest = insertSecurityEvent("AL_TIE", "10.1.1.1", "{}", TIE);
        long middle = insertSecurityEvent("AL_TIE", "10.1.1.2", "{}", TIE);
        long newest = insertSecurityEvent("AL_TIE", "10.9.9.9", "{}", TIE);
        var type = new SecurityEventFilter("AL_TIE", null, null, null, null);

        var first = audit.securityEvents(2, null, null, null, null, type);
        var second = audit.securityEvents(2, first.nextCursor(), null, null, null, type);
        assertThat(first.items()).extracting(SecurityEventView::id).containsExactly(newest, middle);
        assertThat(second.items()).extracting(SecurityEventView::id).containsExactly(oldest);

        assertThat(audit.securityEvents(null, null, null, null, "10.9.9", type).items())
                .extracting(SecurityEventView::id)
                .containsExactly(newest);
        assertThat(audit.securityEvents(
                                null,
                                null,
                                null,
                                null,
                                null,
                                new SecurityEventFilter("AL_TIE", null, "10.1.1", null, null))
                        .items())
                .extracting(SecurityEventView::id)
                .containsExactly(middle, oldest);
    }

    @Test
    @DisplayName("Every registry field is a property of the row the client receives")
    void everyFieldIsARowProperty() {
        assertThat(AuditQuery.LOGS.fields())
                .allSatisfy(field -> assertThat(properties(AuditLogView.class)).contains(field.key()));
        assertThat(AuditQuery.SECURITY_EVENTS.fields())
                .allSatisfy(
                        field -> assertThat(properties(SecurityEventView.class)).contains(field.key()));
    }

    @Test
    @DisplayName("The exports refuse bad option values before the job starts")
    void exportsCheckOptionValues() {
        var exporters = new AuditListExporters();
        assertThat(exporters
                        .auditLogsExporter(audit)
                        .checkOptions(Map.of("event", "U", "userId", "4", "from", "2026-09-01T00:00:00Z")))
                .isEmpty();
        assertThat(exporters
                        .auditLogsExporter(audit)
                        .checkOptions(Map.of("event", "X", "userId", "me", "to", "yesterday")))
                .extracting(FieldErrorItem::field)
                .containsExactlyInAnyOrder("event", "userId", "to");
        assertThat(exporters.auditSecurityEventsExporter(audit).checkOptions(Map.of("from", "soon")))
                .extracting(FieldErrorItem::field)
                .containsExactly("from");
    }

    private static List<String> properties(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
    }

    private static long insertAudit(String table, String rowPk, String event, Instant at, String newRow) {
        return jdbc.sql("""
                        insert into audit_log (table_name, row_pk, event, changed_at, changed_columns, new_row)
                        values (:table, :rowPk, :event, :at, array['state'], cast(:newRow as jsonb))
                        returning id
                        """)
                .param("table", table)
                .param("rowPk", rowPk)
                .param("event", event)
                .param("at", java.sql.Timestamp.from(at))
                .param("newRow", newRow)
                .query(Long.class)
                .single();
    }

    private static long insertSecurityEvent(String type, String ip, String details, Instant at) {
        return jdbc.sql("""
                        insert into security_events (event_type, ip, details, created_at)
                        values (:type, cast(:ip as inet), cast(:details as jsonb), :at)
                        returning id
                        """)
                .param("type", type)
                .param("ip", ip)
                .param("details", details)
                .param("at", java.sql.Timestamp.from(at))
                .query(Long.class)
                .single();
    }
}
