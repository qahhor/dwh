package com.greenwhite.dwh.instance.search;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class SearchStatusTest extends SearchSettingsIntegrationTestSupport {
    @Test void statusSeparatesInstallationDiskFromGenerationCountsAndDoesNotMutateEngine() throws Exception {
        authenticate(Set.of("*.*"), false);
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dependency.healthy").value(true))
                .andExpect(jsonPath("$.dependency.version").value("27.1"))
                .andExpect(jsonPath("$.dependency.installationDiskUsedBytes").value(123456))
                .andExpect(jsonPath("$.generations[0].documentCount").value(69))
                .andExpect(jsonPath("$.generations[0].storageBytes").doesNotExist())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.rebuildRequired").value(false))
                .andExpect(jsonPath("$.lastSuccessfulReconciliation").doesNotExist());
        assertThat(paths).allMatch(path -> path.startsWith("GET "));
        assertThat(jdbc.sql("select count(*) from search_jobs").query(Long.class).single()).isZero();
    }
    @Test void failedHealthReturnsUnknownCountsAndSafeDependencyCode() throws Exception {
        authenticate(Set.of("*.*"), false);
        healthStatus=503;
        responses.put("/health","private-downstream-marker");
        String json = mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dependency.healthy").value(false))
                .andExpect(jsonPath("$.generations[0].documentCount").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("private-downstream-marker", "fixture-key", "http://", "fixture_tasks");
    }

    @Test void missingActiveCollectionRequiresRebuildWithoutPretendingItHasZeroDocuments() throws Exception {
        authenticate(Set.of("*.*"),false);
        responseStatuses.put("/collections/fixture_tasks",404);
        responses.put("/collections/fixture_tasks","private missing collection marker");
        mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.dependency.healthy").value(true))
                .andExpect(jsonPath("$.rebuildRequired").value(true))
                .andExpect(jsonPath("$.generations[0].documentCount").doesNotExist())
                .andExpect(jsonPath("$.generations[0].errorCode").value("COLLECTION_MISSING"));
    }

    @Test void profileChangeKeepsActiveSchemaAndRequiresRebuildWhilePreviewUsesTheActiveProfile() throws Exception {
        authenticate(Set.of("*.*"),false);
        long version=readSettings().path("version").asLong();
        var policy=new com.greenwhite.dwh.instance.search.service.SearchQueryPolicy(3,120,20,"RU",
                com.greenwhite.dwh.instance.search.service.SearchQueryPolicy.defaults().fields());
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(version,policy))).andExpect(status().isOk());
        assertThat(paths).isEmpty();
        mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfile").value("MIXED"))
                .andExpect(jsonPath("$.configuredProfile").value("RU"))
                .andExpect(jsonPath("$.rebuildRequired").value(true));
        mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"delivery\",\"entity\":\"TASK\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeProfile").value("MIXED"))
                .andExpect(jsonPath("$.result.totalHits").value(3));
        assertThat(paths).noneMatch(path -> path.equals("POST /collections"));
    }

    @Test void normalizedMixedDefaultsMatchButTokenizerDriftRequiresRebuild() throws Exception {
        authenticate(Set.of("*.*"),false);
        var normalized=(tools.jackson.databind.node.ObjectNode) mapper.readTree(responses.get("/collections/fixture_tasks"));
        for (var node:normalized.path("fields")) {
            var field=(tools.jackson.databind.node.ObjectNode)node;
            if (!field.has("optional")) field.put("optional",false);
            if (!field.has("facet")) field.put("facet",false);
            if (!field.has("index")) field.put("index",true);
            if (!field.has("sort")) field.put("sort",field.path("type").asString().equals("int64"));
            field.put("locale","");
            if (!field.has("stem")) field.put("stem",false);
        }
        responses.put("/collections/fixture_tasks",mapper.writeValueAsString(normalized));
        mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.generations[0].schemaMatches").value(true))
                .andExpect(jsonPath("$.rebuildRequired").value(false));
        normalized.putArray("token_separators").add("-");
        responses.put("/collections/fixture_tasks",mapper.writeValueAsString(normalized));
        mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.generations[0].schemaMatches").value(false))
                .andExpect(jsonPath("$.rebuildRequired").value(true));
    }

    @Test void statusUsesActiveVerificationAndReportsDatabaseQueueFactsDuringAnEngineOutage() throws Exception {
        authenticate(Set.of("*.*"),false);
        jdbc.sql("update search_generations set verified_at='2026-09-07T08:00:00Z'").update();
        jdbc.sql("insert into search_jobs(id,request_id,action,state,finished_at) values(gen_random_uuid(),gen_random_uuid(),'CHECK','SUCCEEDED','2026-09-07T09:00:00Z')").update();
        jdbc.sql("insert into search_projection_versions(entity_type,entity_id,revision,changed_at) values('TASK',777,2,clock_timestamp()-interval '1 minute'),('TASK',778,1,clock_timestamp())").update();
        jdbc.sql("insert into search_generation_delivery(generation_id,entity_type,entity_id,delivered_revision,attempted_revision,attempts,error_code) select id,'TASK',777,1,2,1,'TRANSIENT' from search_generations").update();
        healthStatus=503;
        mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSuccessfulReconciliation").value("2026-09-07T08:00:00Z"))
                .andExpect(jsonPath("$.generations[0].pendingDeliveries").value(2))
                .andExpect(jsonPath("$.generations[0].failedDeliveries").value(1))
                .andExpect(jsonPath("$.generations[0].queueLagSeconds").value(org.hamcrest.Matchers.greaterThanOrEqualTo(59)))
                .andExpect(jsonPath("$.generations[0].documentCount").doesNotExist());
    }

    @Test void ruRegistrationIsNotReportedAsVerifiedSchemaEvidence() throws Exception {
        authenticate(Set.of("*.*"),false);
        jdbc.sql("update search_generations set schema_profile='RU'").update();
        mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.generations[0].registeredProfile").value("RU"))
                .andExpect(jsonPath("$.generations[0].schemaMatches").doesNotExist())
                .andExpect(jsonPath("$.lastSuccessfulReconciliation").doesNotExist());
    }
}
