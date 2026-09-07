package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.StartJobRequest;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class SearchJobLifecycleIntegrationTest extends SearchDeliveryTestSupport {
    @BeforeEach void admin() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(null,"fixture","fixture@example.invalid",1L,false,Set.of("*.*"),1L,false,0,null));
    }
    @AfterEach void clearContext() { SecurityContext.clear(); }
    @Test void legacySchemaNeverReceivesVerifiedTimestampEvenWhenBodiesHappenToMatch() {
        UUID generation=activeGeneration();
        jdbc.sql("update search_generations set state='LEGACY',schema_version=0 where id=:id").param("id",generation).update();
        user("Legacy");worker.runOnce();
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"CHECK",null)).id();
        for (int i=0;i<30 && !jobRepository.find(job).orElseThrow().state().equals("SUCCEEDED");i++) jobWorker.runOnce();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(generationRepository.find(generation).orElseThrow().verifiedAt()).isNull();
    }
    @Test void storageExhaustionDuringBuildStopsSafelyWithAnExplicitReason() {
        UUID active=activeGeneration();user("Storage");
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"REBUILD",null)).id();
        jobWorker.runOnce();
        var oldCollections=Set.copyOf(collections);
        diskMetrics="{\"system_disk_total_bytes\":\"100\",\"system_disk_used_bytes\":\"99\"}";
        jobWorker.runOnce();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("FAILED");
        assertThat(jobRepository.find(job).orElseThrow().errorCode()).isEqualTo("INSUFFICIENT_SEARCH_STORAGE");
        assertThat(state.snapshot().generationId()).isEqualTo(active);
        assertThat(collections).isEqualTo(oldCollections);
    }
    @Test void batchAcknowledgementsAndRetriesRemainRowSpecific() {
        activeGeneration();long accepted=user("Accepted"),rejected=user("Rejected");
        rejectedImportIds.add(Long.toString(rejected));worker.runOnce();
        assertThat(delivered("USER",accepted)).isOne();assertThat(delivered("USER",rejected)).isZero();
        assertThat(jdbc.sql("select error_code from search_generation_delivery where entity_type='USER' and entity_id=:id")
                .param("id",rejected).query(String.class).single()).isEqualTo("IMPORT_REJECTED");
        assertThat(metricRegistry.find("dwh.search.import.rows").tag("outcome","SUCCESS").counter().count()).isOne();
        assertThat(metricRegistry.find("dwh.search.import.rows").tag("outcome","FAILURE").counter().count()).isOne();
        rejectedImportIds.clear();clock.advance(java.time.Duration.ofSeconds(2));worker.runOnce();
        assertThat(delivered("USER",rejected)).isOne();
        assertThat(metricRegistry.find("dwh.search.delivery.retries").counter().count()).isOne();
    }
    @Test void retryOfFailedRollbackResetsExhaustedClaimsAndReusesTheRetainedGeneration() {
        UUID retained=activeGeneration();
        jdbc.sql("update search_generations set state='RETAINED',task_collection='ret_tasks',project_collection='ret_projects',user_collection='ret_users' where id=:id")
                .param("id",retained).update();
        for (String type:List.of("TASK","PROJECT","USER")) client.ensureCollection(generationRepository.find(retained).orElseThrow().collections().get(type),type);
        activeGeneration();long id=user("Rollback retry");
        jdbc.sql("insert into search_generation_delivery(generation_id,entity_type,entity_id,attempted_revision,delivered_revision,attempts,error_code) values(:generation,'USER',:id,1,0,8,'DELIVERY_FAILED')")
                .param("generation",retained).param("id",id).update();
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"ROLLBACK",retained)).id();runCycle();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("FAILED");
        UUID retry=jobService.retry(job,UUID.randomUUID()).id();
        for (int i=0;i<40 && !Set.of("FAILED","SUCCEEDED").contains(jobRepository.find(retry).orElseThrow().state());i++) runCycle();
        assertThat(jobRepository.find(retry).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(state.snapshot().generationId()).isEqualTo(retained);
    }
    @Test void actualJobAndDeliveryPathsPublishFiniteOperationalMetrics() {
        activeGeneration();user("Metrics");
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"REBUILD",null)).id();
        for (int i=0;i<40 && !jobRepository.find(job).orElseThrow().state().equals("SUCCEEDED");i++) runCycle();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(metricRegistry.find("dwh.search.job.states").tag("state","SUCCEEDED").counter()).isNotNull();
        assertThat(metricRegistry.find("dwh.search.job.duration").timer()).isNotNull();
        assertThat(metricRegistry.find("dwh.search.generation.switches").counter()).isNotNull();
        assertThat(metricRegistry.find("dwh.search.import.rows").tag("outcome","SUCCESS").counter()).isNotNull();
        assertThat(metricRegistry.find("dwh.search.delivery.pending").tag("role","ACTIVE").gauge()).isNotNull();
        assertThat(metricRegistry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isIn("state","action","role","outcome");
            assertThat(tag.getValue()).isIn("SUCCEEDED","RUNNING","VERIFYING","ACTIVATING","QUEUED","REBUILD","ACTIVE","CANDIDATE","SUCCESS");
        }));
    }
    @Test void oversizedIncrementalProjectionPersistsVisibleSafeFailureAndNeverTruncates() {
        activeGeneration();long reporter=user("Reporter"),id=task(reporter,"Large");
        jdbc.sql("update ms_tasks set description_markdown=repeat('x',1100000) where id=:id").param("id",id).update();
        worker.runOnce();
        assertThat(jdbc.sql("select error_code from search_generation_delivery where entity_type='TASK' and entity_id=:id")
                .param("id",id).query(String.class).single()).isEqualTo("DOCUMENT_TOO_LARGE");
        assertThat(documents).doesNotContainKey("tasks/"+id);
    }
    @Test void mismatchCheckStoresSummaryWithoutAdvancingSuccessfulReconciliationOrMutatingTheIndex() {
        UUID active=activeGeneration();long id=user("Before");worker.runOnce();
        var verified=java.time.Instant.parse("2026-09-01T00:00:00Z");
        jdbc.sql("update search_generations set verified_at=:verified where id=:id").param("verified",java.sql.Timestamp.from(verified)).param("id",active).update();
        documents.remove("users/"+id);
        var priorWrites=List.copyOf(writes);
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"CHECK",null)).id();
        for (int i=0;i<30 && !jobRepository.find(job).orElseThrow().state().equals("SUCCEEDED");i++) jobWorker.runOnce();
        var completed=jobRepository.find(job).orElseThrow();
        assertThat(completed.state()).isEqualTo("SUCCEEDED");assertThat(completed.verification().missing()).isOne();
        assertThat(generationRepository.find(active).orElseThrow().verifiedAt()).isEqualTo(verified);
        assertThat(writes).isEqualTo(priorWrites);
    }
    @Test void switchAndSuccessRollBackTogetherWhenAuditStorageRejectsTheTransaction() {
        UUID active=activeGeneration();user("Audit atomicity");
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"REBUILD",null)).id();
        for (int i=0;i<40 && !jobRepository.find(job).orElseThrow().state().equals("ACTIVATING");i++) runCycle();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("ACTIVATING");
        long version=state.snapshot().version();
        jdbc.sql("create function search_fixture_refuse_switch() returns trigger language plpgsql as $$ begin if new.table_name='search_index_state' then raise exception 'fixture audit refusal'; end if; return new; end $$").update();
        jdbc.sql("create trigger search_fixture_refuse_switch before insert on audit_log for each row execute function search_fixture_refuse_switch()").update();
        try {
            jobWorker.runOnce();
            assertThat(state.snapshot().generationId()).isEqualTo(active);
            assertThat(state.snapshot().version()).isEqualTo(version);
            assertThat(generationRepository.find(active).orElseThrow().state()).isEqualTo("ACTIVE");
            assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("FAILED");
            assertThat(jdbc.sql("select count(*) from audit_log where table_name='search_index_state' and new_row->>'job_id'=:job")
                    .param("job",job.toString()).query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("drop trigger search_fixture_refuse_switch on audit_log").update();
            jdbc.sql("drop function search_fixture_refuse_switch()").update();
        }
    }
    @Test void failedDeliveryIsTerminalAndExplicitRetryResetsAttemptsOnTheSameGeneration() {
        activeGeneration();long id=user("Pending");
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"REBUILD",null)).id();
        UUID candidate=jobRepository.find(job).orElseThrow().generationId();
        for (int i=0;i<3;i++) runCycle();
        jdbc.sql("insert into search_generation_delivery(generation_id,entity_type,entity_id,attempted_revision,delivered_revision,attempts,error_code) values(:generation,'USER',:id,1,0,8,'DELIVERY_FAILED')")
                .param("generation",candidate).param("id",id).update();
        runCycle();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("FAILED");
        assertThat(jobRepository.find(job).orElseThrow().failedCount()).isOne();
        UUID retry=jobService.retry(job,UUID.randomUUID()).id();
        for (int i=0;i<40 && !Set.of("SUCCEEDED","FAILED").contains(jobRepository.find(retry).orElseThrow().state());i++) runCycle();
        assertThat(jobRepository.find(retry).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(state.snapshot().generationId()).isEqualTo(candidate);
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("FAILED");
    }
    @Test void completedProofBecomesStaleEvenIfNewRevisionWasAlreadyDelivered() {
        activeGeneration();long id=user("Before");worker.runOnce();
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"REBUILD",null)).id();
        for (int i=0;i<40 && !jobRepository.find(job).orElseThrow().state().equals("ACTIVATING");i++) runCycle();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("ACTIVATING");
        UUID old=state.snapshot().generationId();
        var candidate=generationRepository.find(jobRepository.find(job).orElseThrow().generationId()).orElseThrow();
        users.updateUser(id,"After",null,null,null,null,null,null,null,null,id);
        worker.runGeneration(candidate.delivery(state.snapshot().version()));
        assertThat(generationRepository.pending(candidate.id())).isZero();
        jobWorker.runOnce();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(state.snapshot().generationId()).isEqualTo(old);
        for (int i=0;i<40 && !jobRepository.find(job).orElseThrow().state().equals("SUCCEEDED");i++) runCycle();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    @Test void restartInvalidatesOldProofAndOwnerButResumesTheSameJob() {
        activeGeneration();user("Restart");
        UUID job=jobService.start(new StartJobRequest(UUID.randomUUID(),"REBUILD",null)).id();
        for (int i=0;i<20 && !jobRepository.find(job).orElseThrow().state().equals("VERIFYING");i++) runCycle();
        UUID oldOwner=owner;
        var obsolete=jobWorker;
        recreateWorker();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(jobRepository.checkpoint(job,oldOwner,"ACTIVATING",999,0,null)).isFalse();
        obsolete.runOnce();
        for (int i=0;i<40 && !jobRepository.find(job).orElseThrow().state().equals("SUCCEEDED");i++) runCycle();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("select count(*) from search_jobs").query(Long.class).single()).isOne();
    }
}
