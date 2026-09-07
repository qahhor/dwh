package com.greenwhite.dwh.instance.search;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SearchJobIntegrationTest extends SearchSettingsIntegrationTestSupport {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
            "{}","{\"requestId\":null,\"action\":\"CHECK\"}","{\"requestId\":\"1-1-1-1-1\",\"action\":\"CHECK\"}",
            "{\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"action\":\"DELETE\"}",
            "{\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"action\":\"CHECK\",\"generationId\":\"bad\"}",
            "{\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"action\":\"CHECK\",\"raw\":true}"})
    void invalidRequestsAreStructuredBadRequestsWithoutCreatingJobs(String json) throws Exception {
        authenticate(Set.of("*.*"),false);
        mvc.perform(auth(post("/api/v1/search/jobs")).content(json)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        assertThat(jdbc.sql("select count(*) from search_jobs").query(Long.class).single()).isZero();
    }
    @Test void nonexistentTargetsAndInvalidHistoryBoundariesAreStructuredErrors() throws Exception {
        authenticate(Set.of("*.*"),false);
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(UUID.randomUUID(),"CHECK",UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(auth(get("/api/v1/search/jobs/"+UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/search/jobs")).param("limit","0")).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/search/jobs")).param("limit","101")).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/search/jobs")).param("cursor","not-a-valid-cursor")).andExpect(status().isBadRequest());
    }
    @Test void rebuildAllocatesAFrozenNewGenerationAndNeverClearsTheActiveIndex() throws Exception {
        authenticate(Set.of("*.*"),false);
        ampleStorage();
        UUID active=jdbc.sql("select active_generation_id from search_index_state where id=1").query(UUID.class).single();
        var policy = new com.greenwhite.dwh.instance.search.service.SearchQueryPolicy(10,120,20,"RU",
                com.greenwhite.dwh.instance.search.service.SearchQueryPolicy.defaults().fields());
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(readSettings().path("version").asLong(),policy))).andExpect(status().isOk());
        long settingsVersion=readSettings().path("version").asLong();
        UUID job=start(UUID.randomUUID(),"REBUILD",null);
        UUID candidate=jdbc.sql("select generation_id from search_jobs where id=:id").param("id",job).query(UUID.class).single();
        assertThat(candidate).isNotEqualTo(active);
        assertThat(jdbc.sql("select active_generation_id from search_index_state where id=1").query(UUID.class).single()).isEqualTo(active);
        assertThat(jdbc.sql("select task_collection from search_generations where id=:id").param("id",candidate).query(String.class).single())
                .isEqualTo("cms_"+candidate.toString().replace("-","")+"_tasks");
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(readSettings().path("version").asLong(),
                com.greenwhite.dwh.instance.search.service.SearchQueryPolicy.defaults()))).andExpect(status().isOk());
        assertThat(jdbc.sql("select schema_profile from search_generations where id=:id").param("id",candidate).query(String.class).single()).isEqualTo("RU");
        assertThat(jdbc.sql("select settings_version from search_generations where id=:id").param("id",candidate).query(Long.class).single()).isEqualTo(settingsVersion);
        assertThat(paths).noneMatch(path -> path.startsWith("DELETE ") || path.startsWith("POST /collections"));
    }

    @Test void registeredFailedBuildsCountTowardFourGenerationCapAndRetryReusesTheGeneration() throws Exception {
        authenticate(Set.of("*.*"),false); ampleStorage();
        UUID first=null;
        for (int i=0;i<3;i++) {
            UUID job=start(UUID.randomUUID(),"REBUILD",null);
            if (i==0) first=job;
            mvc.perform(auth(post("/api/v1/search/jobs/"+job+"/cancel"))).andExpect(status().isAccepted());
        }
        assertThat(jdbc.sql("select count(*) from search_generations").query(Long.class).single()).isEqualTo(4);
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(UUID.randomUUID(),"REBUILD",null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("GENERATION_LIMIT_REACHED"));
        UUID generation=jdbc.sql("select generation_id from search_jobs where id=:id").param("id",first).query(UUID.class).single();
        String retry=mapper.readTree(mvc.perform(auth(post("/api/v1/search/jobs/"+first+"/retry"))
                .content("{\"requestId\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).path("id").asString();
        assertThat(jdbc.sql("select generation_id from search_jobs where id=:id").param("id",UUID.fromString(retry)).query(UUID.class).single()).isEqualTo(generation);
        assertThat(jdbc.sql("select count(*) from search_generations").query(Long.class).single()).isEqualTo(4);
    }

    @Test void unhealthyUnknownOrExhaustedStorageRefusesAllocation() throws Exception {
        authenticate(Set.of("*.*"),false);
        responses.put("/metrics.json","{\"system_disk_used_bytes\":\"1\",\"system_disk_total_bytes\":\"100\"}");
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(UUID.randomUUID(),"REBUILD",null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("INSUFFICIENT_SEARCH_STORAGE"));
        responses.put("/metrics.json","{}");
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(UUID.randomUUID(),"REBUILD",null)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.detail").value("SEARCH_STORAGE_UNAVAILABLE"));
        ampleStorage(); healthStatus=503;
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(UUID.randomUUID(),"REBUILD",null))).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.sql("select count(*) from search_generations").query(Long.class).single()).isOne();
        assertThat(jdbc.sql("select count(*) from search_jobs").query(Long.class).single()).isZero();
    }

    @Test void concurrentBuildStartsSerializeToOneAcceptedAndOneConflict() throws Exception {
        authenticate(Set.of("*.*"),false); ampleStorage();
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready=new java.util.concurrent.CountDownLatch(2);
            var go=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> action=() -> {
                ready.countDown(); go.await();
                return mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(UUID.randomUUID(),"REBUILD",null)))
                        .andReturn().getResponse().getStatus();
            };
            var first=executor.submit(action); var second=executor.submit(action);
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(java.util.List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(202,409);
        }
        assertThat(jdbc.sql("select count(*) from search_generations").query(Long.class).single()).isEqualTo(2);
    }

    private void ampleStorage() { responses.put("/metrics.json","{\"system_disk_used_bytes\":\"123456\",\"system_disk_total_bytes\":\"1073741824\"}"); }
    @Test void requestedTargetAndActionArePartOfTheDurableReplayIdentity() throws Exception {
        authenticate(Set.of("*.*"),false);
        UUID generation = jdbc.sql("select active_generation_id from search_index_state where id=1").query(UUID.class).single();
        UUID request = UUID.randomUUID();
        start(request,"CHECK",null);
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(request,"CHECK",generation))).andExpect(status().isConflict());
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(request,"REBUILD",null))).andExpect(status().isConflict());
    }

    @Test void legacyUnknownRequestReplayFailsSafelyAndHistoryRemainsReadable() throws Exception {
        authenticate(Set.of("*.*"),false);
        UUID request = UUID.randomUUID();
        UUID job = start(request,"CHECK",null);
        jdbc.sql("update search_jobs set request_metadata_recorded=false where id=:id").param("id",job).update();
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(request,"CHECK",null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("REQUEST_HISTORY_UNAVAILABLE"));
        mvc.perform(auth(get("/api/v1/search/jobs/"+job))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(job.toString()));
    }

    @Test void cancellationIsDurableBeforeActivationAndRejectedOnceActivating() throws Exception {
        authenticate(Set.of("*.*"),false);
        UUID first = start(UUID.randomUUID(),"CHECK",null);
        mvc.perform(auth(post("/api/v1/search/jobs/"+first+"/cancel"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        UUID second = start(UUID.randomUUID(),"CHECK",null);
        jdbc.sql("update search_jobs set state='ACTIVATING' where id=:id").param("id",second).update();
        mvc.perform(auth(post("/api/v1/search/jobs/"+second+"/cancel"))).andExpect(status().isConflict());
        assertThat(jdbc.sql("select state from search_jobs where id=:id").param("id",second).query(String.class).single()).isEqualTo("ACTIVATING");
        assertThat(jdbc.sql("select count(*) from audit_log where table_name='search_jobs' and new_row->>'job_id'=:job and new_row->>'operation'='CANCEL'")
                .param("job",first.toString()).query(Long.class).single()).isOne();
    }

    @Test void retryKeepsOriginalAttemptAndReplayWhileReusingItsGeneration() throws Exception {
        authenticate(Set.of("*.*"),false);
        UUID originalRequest = UUID.randomUUID();
        UUID job = start(originalRequest,"CHECK",null);
        UUID generation = jdbc.sql("select generation_id from search_jobs where id=:id").param("id",job).query(UUID.class).single();
        jdbc.sql("update search_jobs set state='FAILED',error_code='IMPORT_REJECTED',finished_at=clock_timestamp() where id=:id")
                .param("id",job).update();
        UUID retryRequest = UUID.randomUUID();
        String retryBody = "{\"requestId\":\""+retryRequest+"\"}";
        String retryId = mapper.readTree(mvc.perform(auth(post("/api/v1/search/jobs/"+job+"/retry")).content(retryBody))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asString();
        assertThat(retryId).isNotEqualTo(job.toString());
        mvc.perform(auth(post("/api/v1/search/jobs/"+job+"/retry")).content(retryBody)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(retryId));
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(originalRequest,"CHECK",null))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(job.toString())).andExpect(jsonPath("$.state").value("FAILED"));
        assertThat(jdbc.sql("select generation_id from search_jobs where id=:id").param("id",UUID.fromString(retryId)).query(UUID.class).single()).isEqualTo(generation);
        assertThat(jdbc.sql("select retry_of_job_id from search_jobs where id=:id").param("id",UUID.fromString(retryId)).query(UUID.class).single()).isEqualTo(job);
        assertThat(jdbc.sql("select count(*) from search_jobs").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select new_row->>'operation' from audit_log where table_name='search_jobs' and new_row->>'job_id'=:job")
                .param("job",retryId).query(String.class).single()).isEqualTo("RETRY");
    }

    @Test void historyIsKeysetBoundedAndRequiresSearchAdministratorAccess() throws Exception {
        authenticate(Set.of("*.*"),false);
        for (int i=0;i<3;i++) start(UUID.randomUUID(),"CHECK",null);
        var first = mapper.readTree(mvc.perform(auth(get("/api/v1/search/jobs")).param("limit","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andReturn().getResponse().getContentAsString());
        mvc.perform(auth(get("/api/v1/search/jobs")).param("limit","2").param("cursor",first.path("nextCursor").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        authenticate(Set.of("platform.search.view"),false);
        mvc.perform(auth(get("/api/v1/search/jobs"))).andExpect(status().isForbidden());
        authenticate(Set.of("platform.search.view"),true);
        mvc.perform(auth(get("/api/v1/search/jobs"))).andExpect(status().isOk());
        mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(UUID.randomUUID(),"CHECK",null))).andExpect(status().isForbidden());
    }

    private UUID start(UUID request, String action, UUID target) throws Exception {
        return UUID.fromString(mapper.readTree(mvc.perform(auth(post("/api/v1/search/jobs")).content(jobJson(request,action,target)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asString());
    }
    private String jobJson(UUID request,String action,UUID target) {
        return "{\"requestId\":\""+request+"\",\"action\":\""+action+"\""+(target==null ? "" : ",\"generationId\":\""+target+"\"")+"}";
    }
    @Test void checkStartIsAsynchronousAndReplayRetainsItsOriginalImplicitTarget() throws Exception {
        authenticate(Set.of("*.*"), false);
        UUID request = UUID.randomUUID();
        String body = "{\"requestId\":\"" + request + "\",\"action\":\"CHECK\"}";
        var first = mvc.perform(auth(post("/api/v1/search/jobs")).content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("QUEUED")).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(first).path("id").asString();
        jdbc.sql("update search_index_state set active_generation_id=null where id=1").update();
        mvc.perform(auth(post("/api/v1/search/jobs")).content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id));
        assertThat(jdbc.sql("select count(*) from search_jobs").query(Long.class).single()).isOne();
    }

}
