package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.StartJobRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class SearchJobResourceIntegrationTest extends SearchDeliveryTestSupport {
    @BeforeEach void principal() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(null,"fixture","fixture@example.invalid",1L,false,Set.of("*.*"),1L,false,0,null));
    }
    @AfterEach void clearPrincipal() { SecurityContext.clear(); }
    @Test void jobWorkerRejectsBusinessTransactionBeforeAnyTransportOrClaim() {
        activeGeneration();UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"CHECK",null)).id();
        int before=requests.get();
        assertThatThrownBy(() -> tx.executeWithoutResult(transaction -> jobWorker.runOnce()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(requests.get()).isEqualTo(before);
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("QUEUED");
    }

    @ParameterizedTest @ValueSource(strings={"CANCEL","OWNER","SHUTDOWN"})
    void suspendedProofAndCursorAreClosedWithoutTouchingAnotherSessionsTemporaryObjects(String stop) throws Exception {
        activeGeneration();
        jdbc.sql("insert into md_users(name,login,email) select 'Fixture '||n,'resource-'||n,'resource-'||n||'@example.invalid' from generate_series(1,205) n").update();
        jdbc.sql("insert into search_projection_versions(entity_type,entity_id,revision) select 'USER',id,1 from md_users").update();
        for (int i=0;i<3;i++) worker.runOnce();
        var writesBefore=List.copyOf(writes);
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"CHECK",null)).id();
        jobWorker.runOnce();
        try (var connection=database.getConnection()) {
            var other=JdbcClient.create(new SingleConnectionDataSource(connection,true));
            other.sql("create temporary table search_reconcile_sentinel(id int)").update();
            for (int i=0;i<6;i++) jobWorker.runOnce();
            assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("VERIFYING");
            assertThat(jobRepository.find(job).orElseThrow().processedCount()).isEqualTo(205);
            assertThat(proofObjects()).isEqualTo(2);
            assertThat(jdbc.sql("select count(*) from pg_stat_activity where datname=current_database() and state='idle in transaction'").query(Long.class).single()).isZero();
            switch(stop) {
                case "CANCEL" -> { jobService.cancel(job);jobWorker.runOnce(); }
                case "OWNER" -> { state.recoverOwnership(UUID.randomUUID());jobWorker.runOnce(); }
                default -> jobWorker.close();
            }
            assertThat(proofObjects()).isZero();
            assertThat(other.sql("select count(*) from search_reconcile_sentinel").query(Long.class).single()).isZero();
            assertThat(writes).isEqualTo(writesBefore);
        }
    }

    @Test void stalledExportCannotKeepTheProofOrPreventTheNextActiveDeliveryCycle() throws Exception {
        activeGeneration();long id=user("Before");worker.runOnce();
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"CHECK",null)).id();
        jobWorker.runOnce();
        for (int i=0;i<5;i++) jobWorker.runOnce();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        beforeRequest=exchange -> {
            if (!exchange.getRequestURI().getPath().endsWith("/users/documents/export")) return;
            try {
                exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
                entered.countDown();SearchRevisionIntegrationTest.await(release);
            } catch (java.io.IOException ignored) { /* expected once the timed-out client closes */ }
        };
        try (var executor=Executors.newSingleThreadExecutor()) {
            long started=System.nanoTime();
            var cycle=executor.submit(jobWorker::runOnce);
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            cycle.get(5,TimeUnit.SECONDS);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(5000);
            assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("FAILED");
            assertThat(jobRepository.find(job).orElseThrow().errorCode()).isEqualTo("SEARCH_DEPENDENCY_FAILED");
            assertThat(proofObjects()).isZero();
        } finally { release.countDown();beforeRequest=exchange -> {}; }
        users.updateUser(id,"After",null,null,null,null,null,null,null,null,id);worker.runOnce();
        assertThat(documents.get("users/"+id)).containsEntry("name","After");
        jobWorker.close();
    }
    private long proofObjects() {
        return jdbc.sql("select count(*) from pg_class where relpersistence='t' and relname in ('search_reconcile_index','search_reconcile_source')")
                .query(Long.class).single();
    }
}
