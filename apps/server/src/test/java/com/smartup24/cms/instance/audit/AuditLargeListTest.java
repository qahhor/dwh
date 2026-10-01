package com.smartup24.cms.instance.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.audit.api.AuditLogFilter;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditListService;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.5, acceptance: on an audit log of fifty million rows the first page of the list, with and
 * without a filter, and the totals of the audit screen answer in under 300 ms at the 95th percentile.
 *
 * <p>Tagged {@code audit-large}: filling the log takes minutes and gigabytes, so the everyday suite skips it;
 * {@link AuditListIntegrationTest} proves the estimate there. Run it on its own, optionally with fewer rows:
 *
 * <pre>
 * mvn -pl apps/server test -Paudit-large [-Daudit.large.rows=5000000]
 * </pre>
 */
@Tag("audit-large")
class AuditLargeListTest {

    private static final Logger log = LoggerFactory.getLogger(AuditLargeListTest.class);
    private static final long ROWS = Long.getLong("audit.large.rows", 50_000_000L);
    private static final int RUNS = 40;
    private static final long P95_MILLIS = 300;

    static JdbcClient jdbc;
    static AuditListService audit;
    static AuditLogService auditLog;

    @BeforeAll
    static void fillTheLog() {
        var ds = TestDatabases.migratedCopy("smc_audit_large_test");
        jdbc = JdbcClient.create(ds);
        var repository = new AuditLogRepository(jdbc, new ObjectMapper());
        auditLog = new AuditLogService(repository, null, new AuditDataRedactor());
        audit = new AuditListService(new QueryListRepository(jdbc), repository, auditLog);

        long started = System.nanoTime();
        // A year of changes, one every 0.6 s, over ten thousand records of five tables; batches keep the WAL small.
        long batch = 5_000_000;
        for (long from = 1; from <= ROWS; from += batch) {
            jdbc.sql("""
                            insert into audit_log (table_name, row_pk, event, changed_by, changed_at, changed_columns,
                                                   new_row)
                            select (array['md_users', 'ms_tasks', 'ms_notes', 'md_roles', 'ms_task_projects'])[g % 5 + 1],
                                   (g % 10000)::text,
                                   (array['I', 'U', 'D'])[g % 3 + 1],
                                   null,
                                   now() - (g * interval '600 milliseconds'),
                                   array['state'],
                                   jsonb_build_object('state', 'A')
                            from generate_series(:from, :to) g
                            """)
                    .param("from", from)
                    .param("to", Math.min(ROWS, from + batch - 1))
                    .update();
        }
        jdbc.sql("analyze audit_log").update();
        jdbc.sql("analyze security_events").update();
        log.info("audit_large rows={} filled_in={}s", ROWS, (System.nanoTime() - started) / 1_000_000_000L);
    }

    @Test
    @DisplayName("3.5: the first page of the audit list takes under 300 ms at p95 on the whole log")
    void firstPageOfTheWholeLog() {
        var none = new AuditLogFilter(null, null, null, null, null, null);
        var page = audit.logs(null, null, null, null, null, none);

        assertThat(page.items()).hasSize(50);
        assertThat(page.totalExact()).isFalse();
        assertThat(page.totalEstimated()).isGreaterThan(ROWS / 2);
        assertThat(p95("whole log", () -> audit.logs(null, null, null, null, null, none)))
                .isLessThan(P95_MILLIS);
    }

    @Test
    @DisplayName("3.5: the first page of one record's history takes under 300 ms at p95")
    void firstPageOfOneRecord() {
        var record = new AuditLogFilter("ms_tasks", "4321", null, null, null, null);

        assertThat(audit.logs(null, null, null, null, null, record).items()).isNotEmpty();
        assertThat(p95("one record", () -> audit.logs(null, null, null, null, null, record)))
                .isLessThan(P95_MILLIS);
    }

    @Test
    @DisplayName("3.5: the totals of the audit screen take under 300 ms at p95")
    void totalsOfTheAuditScreen() {
        var repository = new AuditLogRepository(jdbc, new ObjectMapper());

        assertThat(repository.getAuditStats().totalAuditLogs()).isGreaterThan(ROWS / 2);
        assertThat(p95("totals", repository::getAuditStats)).isLessThan(P95_MILLIS);
    }

    private static long p95(String what, Supplier<?> call) {
        // Every answer is checked, so no call is optimized away; the first one warms the plan cache.
        Objects.requireNonNull(call.get(), what);
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long started = System.nanoTime();
            Objects.requireNonNull(call.get(), what);
            millis.add((System.nanoTime() - started) / 1_000_000L);
        }
        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(RUNS * 0.95) - 1);
        log.info("audit_large {} rows={} p50={}ms p95={}ms", what, ROWS, millis.get(RUNS / 2), p95);
        return p95;
    }
}
