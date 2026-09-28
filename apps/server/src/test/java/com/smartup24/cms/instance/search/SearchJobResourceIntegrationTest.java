package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.*;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos.StartJobRequest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.IllegalTransactionStateException;

class SearchJobResourceIntegrationTest extends SearchDeliveryTestSupport {
    @BeforeEach
    void principal() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                null, "fixture", "fixture@example.invalid", 1L, false, Set.of("*.*"), 1L, false, 0, null));
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContext.clear();
    }

    @Test
    void jobWorkerRejectsBusinessTransactionBeforeAnyTransportOrClaim() {
        activeGeneration();
        UUID job = jobService
                .start(new StartJobRequest(UUID.randomUUID(), "CHECK", null))
                .id();
        int before = requests.get();
        assertThatThrownBy(() -> tx.executeWithoutResult(transaction -> jobWorker.runOnce()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(requests.get()).isEqualTo(before);
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("QUEUED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCEL", "OWNER", "SHUTDOWN"})
    void suspendedProofAndCursorAreClosedWithoutTouchingAnotherSessionsTemporaryObjects(String stop) throws Exception {
        UUID generation = activeGeneration();
        jdbc.sql(
                        "insert into md_users(name,login,email) select 'Fixture '||n,'resource-'||n,'resource-'||n||'@example.invalid' from generate_series(1,205) n")
                .update();
        jdbc.sql(
                        "insert into search_projection_versions(entity_type,entity_id,revision) select 'USER',id,1 from md_users")
                .update();
        deliverAll(generation, 205);
        var writesBefore = List.copyOf(writes);
        UUID job = jobService
                .start(new StartJobRequest(UUID.randomUUID(), "CHECK", null))
                .id();
        jobWorker.runOnce();
        try (var connection = separateSession()) {
            var other = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            other.sql("create temporary table search_reconcile_sentinel(id int)")
                    .update();
            // Three schema checks, two empty exports and one user page: the proof is suspended mid-export,
            // far short of the 14+ cycles a complete proof needs, whatever size its time-bounded pages take.
            for (int i = 0; i < 6; i++) jobWorker.runOnce();
            assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("VERIFYING");
            assertThat(jobRepository.find(job).orElseThrow().processedCount()).isEqualTo(205);
            assertThat(proofObjects()).isEqualTo(2);
            assertThat(jdbc.sql(
                                    "select count(*) from pg_stat_activity where datname=current_database() and state='idle in transaction'")
                            .query(Long.class)
                            .single())
                    .isZero();
            switch (stop) {
                case "CANCEL" -> {
                    jobService.cancel(job);
                    jobWorker.runOnce();
                }
                case "OWNER" -> {
                    state.recoverOwnership(UUID.randomUUID());
                    jobWorker.runOnce();
                }
                default -> jobWorker.close();
            }
            assertThat(proofObjects()).isZero();
            assertThat(other.sql("select count(*) from search_reconcile_sentinel")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(writes).isEqualTo(writesBefore);
        }
    }

    @Test
    void stalledExportCannotKeepTheProofOrPreventTheNextActiveDeliveryCycle() throws Exception {
        activeGeneration();
        long id = user("Before");
        worker.runOnce();
        UUID job = jobService
                .start(new StartJobRequest(UUID.randomUUID(), "CHECK", null))
                .id();
        jobWorker.runOnce();
        for (int i = 0; i < 5; i++) jobWorker.runOnce();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        beforeRequest = exchange -> {
            if (!exchange.getRequestURI().getPath().endsWith("/users/documents/export")) return;
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                entered.countDown();
                SearchRevisionIntegrationTest.await(release);
            } catch (java.io.IOException ignored) {
                /* expected once the timed-out client closes */
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            long started = System.nanoTime();
            var cycle = executor.submit(jobWorker::runOnce);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            cycle.get(5, TimeUnit.SECONDS);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isLessThan(5000);
            assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("FAILED");
            assertThat(jobRepository.find(job).orElseThrow().errorCode()).isEqualTo("SEARCH_DEPENDENCY_FAILED");
            assertThat(proofObjects()).isZero();
        } finally {
            release.countDown();
            beforeRequest = exchange -> {};
        }
        users.updateUser(id, "After", null, null, null, null, null, null, null, null, id);
        worker.runOnce();
        assertThat(documents.get("users/" + id)).containsEntry("name", "After");
        jobWorker.close();
    }
    /**
     * A delivery cycle claims at most 100 rows, and a claim that fails transiently (import read timeout, connection
     * failure under a loaded build) waits out a backoff measured on the fixture clock. Deliver every row before the
     * job starts so the proof always sees a complete generation instead of racing the delivery.
     */
    private void deliverAll(UUID generation, long rows) {
        for (int cycle = 0; cycle < 20 && generationRepository.processed(generation) < rows; cycle++) {
            worker.runOnce();
            clock.advance(Duration.ofMinutes(6));
        }
        assertThat(generationRepository.processed(generation)).isEqualTo(rows);
    }

    private long proofObjects() {
        return jdbc.sql(
                        "select count(*) from pg_class where relpersistence='t' and relname in ('search_reconcile_index','search_reconcile_source')")
                .query(Long.class)
                .single();
    }
}
