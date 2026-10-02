package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartup24.cms.instance.search.service.SearchEntity;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SearchStatusTest extends SearchSettingsIntegrationTestSupport {

    private static final String TASKS = SearchTestEntities.TASKS;
    private static final String PROJECTS = SearchTestEntities.PROJECTS;
    private static final String USERS = SearchTestEntities.USERS;

    @Test
    void statusIncludesBoundedRecentJobsAndOnlyVerifiableRetainedRollbackTargets() throws Exception {
        authenticate(Set.of("*.*"), false);
        var target = UUID.randomUUID();
        jdbc.sql(
                        "insert into search_generations(id,state,schema_version,schema_profile,settings_version,discovery_entity,verified_at) values(:id,'RETAINED',1,'MIXED',1,'DONE',clock_timestamp())")
                .param("id", target)
                .update();
        for (SearchEntity entity : ENTITIES.all()) {
            collection(target, entity, entity.collection("ret_"));
            var schema = (ObjectNode) mapper.readTree(responses.get("/collections/" + entity.collection("fixture_")));
            schema.put("name", entity.collection("ret_"));
            responses.put("/collections/" + entity.collection("ret_"), mapper.writeValueAsString(schema));
        }
        jdbc.sql(
                        "insert into search_generations(id,state,schema_version,schema_profile,settings_version) values(:id,'RETAINED',0,'MIXED',1)")
                .param("id", UUID.randomUUID())
                .update();
        for (int i = 0; i < 22; i++)
            jdbc.sql(
                            "insert into search_jobs(id,request_id,action,generation_id,state) values(:id,:request,'CHECK',:target,'SUCCEEDED')")
                    .param("id", UUID.randomUUID())
                    .param("request", UUID.randomUUID())
                    .param("target", target)
                    .update();
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs.length()").value(20))
                .andExpect(jsonPath("$.rollbackTargets.length()").value(1))
                .andExpect(jsonPath("$.rollbackTargets[0].id").value(target.toString()));
    }

    @Test
    void generationCountsPreserveDistinctEntitiesAndValidZero() throws Exception {
        authenticate(Set.of("*.*"), false);
        distinctEntityCounts();
        String json = mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                // 0 tasks, 13 projects, 7 users and 23 of each other entity.
                .andExpect(jsonPath("$.generations[0].documentCount").value(66))
                .andExpect(jsonPath(count(TASKS)).value(0))
                .andExpect(jsonPath(count(PROJECTS)).value(13))
                .andExpect(jsonPath(count(USERS)).value(7))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(mapper.readTree(json)
                        .path("generations")
                        .get(0)
                        .path("entityDocumentCounts")
                        .propertyNames())
                .containsExactlyInAnyOrderElementsOf(ENTITIES.codes());
        assertThat(json).doesNotContain("fixture_entity");
    }

    @Test
    void unavailableCollectionKeepsOtherEntityCountsKnown() throws Exception {
        authenticate(Set.of("*.*"), false);
        distinctEntityCounts();
        responseStatuses.put("/collections/" + collection(PROJECTS), 503);
        String json = mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generations[0].documentCount").doesNotExist())
                .andExpect(jsonPath(count(TASKS)).value(0))
                .andExpect(jsonPath(count(USERS)).value(7))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var counts = mapper.readTree(json).path("generations").get(0).path("entityDocumentCounts");
        assertThat(counts.has(PROJECTS)).isTrue();
        assertThat(counts.path(PROJECTS).isNull()).isTrue();
    }

    private static void distinctEntityCounts() {
        for (var entry : Map.of(collection(TASKS), 0, collection(PROJECTS), 13, collection(USERS), 7)
                .entrySet()) {
            String path = "/collections/" + entry.getKey();
            var schema = (ObjectNode) mapper.readTree(responses.get(path));
            schema.put("num_documents", entry.getValue());
            responses.put(path, mapper.writeValueAsString(schema));
        }
    }

    @Test
    void statusSeparatesInstallationDiskFromGenerationCountsAndDoesNotMutateEngine() throws Exception {
        authenticate(Set.of("*.*"), false);
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependency.healthy").value(true))
                .andExpect(jsonPath("$.dependency.version").value("27.1"))
                .andExpect(jsonPath("$.dependency.installationDiskUsedBytes").value(123456))
                .andExpect(jsonPath("$.generations[0].documentCount")
                        .value(23 * ENTITIES.all().size()))
                .andExpect(jsonPath("$.generations[0].storageBytes").doesNotExist())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.rebuildRequired").value(false))
                .andExpect(jsonPath("$.lastSuccessfulReconciliation").doesNotExist());
        assertThat(paths).allMatch(path -> path.startsWith("GET "));
        assertThat(jdbc.sql("select count(*) from search_jobs")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void anEntityWithoutACollectionInTheActiveGenerationRequiresARebuild() throws Exception {
        // ADR-0032, 10.3: an entity that declares the search after the last rebuild has no collection yet.
        authenticate(Set.of("*.*"), false);
        jdbc.sql("delete from search_generation_collections where entity_type = :type")
                .param("type", SearchTestEntities.ORDERS)
                .update();
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generations[0].schemaMatches").value(true))
                .andExpect(jsonPath("$.rebuildRequired").value(true));
    }

    @Test
    void failedHealthReturnsUnknownCountsAndSafeDependencyCode() throws Exception {
        authenticate(Set.of("*.*"), false);
        healthStatus = 503;
        responses.put("/health", "private-downstream-marker");
        String json = mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependency.healthy").value(false))
                .andExpect(jsonPath("$.generations[0].documentCount").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(json).doesNotContain("private-downstream-marker", "fixture-key", "http://", "fixture_entity");
        var counts = mapper.readTree(json).path("generations").get(0).path("entityDocumentCounts");
        assertThat(counts.propertyNames()).containsExactlyInAnyOrderElementsOf(ENTITIES.codes());
        for (String entity : ENTITIES.codes())
            assertThat(counts.path(entity).isNull()).isTrue();
    }

    @Test
    void missingActiveCollectionRequiresRebuildWithoutPretendingItHasZeroDocuments() throws Exception {
        authenticate(Set.of("*.*"), false);
        responseStatuses.put("/collections/" + collection(TASKS), 404);
        responses.put("/collections/" + collection(TASKS), "private missing collection marker");
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependency.healthy").value(true))
                .andExpect(jsonPath("$.rebuildRequired").value(true))
                .andExpect(jsonPath("$.generations[0].documentCount").doesNotExist())
                .andExpect(jsonPath("$.generations[0].errorCode").value("COLLECTION_MISSING"));
    }

    @Test
    void profileChangeKeepsActiveSchemaAndRequiresRebuildWhilePreviewUsesTheActiveProfile() throws Exception {
        authenticate(Set.of("*.*"), false);
        long version = readSettings().path("version").asLong();
        var policy = new SearchQueryPolicy(
                3, 120, 20, "RU", SearchQueryPolicy.defaults().fields());
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(version, policy)))
                .andExpect(status().isOk());
        assertThat(paths).isEmpty();
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfile").value("MIXED"))
                .andExpect(jsonPath("$.configuredProfile").value("RU"))
                .andExpect(jsonPath("$.rebuildRequired").value(true));
        mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"delivery\",\"entity\":\"ms.tasks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfile").value("MIXED"))
                .andExpect(jsonPath("$.result.totalHits").value(3));
        assertThat(paths).noneMatch(path -> path.equals("POST /collections"));
    }

    @Test
    void normalizedMixedDefaultsMatchButTokenizerDriftRequiresRebuild() throws Exception {
        authenticate(Set.of("*.*"), false);
        String path = "/collections/" + collection(TASKS);
        var normalized = (ObjectNode) mapper.readTree(responses.get(path));
        for (var node : normalized.path("fields")) {
            var field = (ObjectNode) node;
            if (!field.has("optional")) field.put("optional", false);
            if (!field.has("facet")) field.put("facet", false);
            if (!field.has("index")) field.put("index", true);
            if (!field.has("sort"))
                field.put("sort", field.path("type").asString().equals("int64"));
            field.put("locale", "");
            if (!field.has("stem")) field.put("stem", false);
        }
        responses.put(path, mapper.writeValueAsString(normalized));
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generations[0].schemaMatches").value(true))
                .andExpect(jsonPath("$.rebuildRequired").value(false));
        normalized.putArray("token_separators").add("-");
        responses.put(path, mapper.writeValueAsString(normalized));
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generations[0].schemaMatches").value(false))
                .andExpect(jsonPath("$.rebuildRequired").value(true));
    }

    @Test
    void statusUsesActiveVerificationAndReportsDatabaseQueueFactsDuringAnEngineOutage() throws Exception {
        authenticate(Set.of("*.*"), false);
        jdbc.sql("update search_generations set verified_at='2026-09-07T08:00:00Z'")
                .update();
        jdbc.sql(
                        "insert into search_jobs(id,request_id,action,state,finished_at) values(gen_random_uuid(),gen_random_uuid(),'CHECK','SUCCEEDED','2026-09-07T09:00:00Z')")
                .update();
        jdbc.sql(
                        "insert into search_projection_versions(entity_type,entity_id,revision,changed_at) values('ms.tasks',777,2,clock_timestamp()-interval '1 minute'),('ms.tasks',778,1,clock_timestamp())")
                .update();
        jdbc.sql(
                        "insert into search_generation_delivery(generation_id,entity_type,entity_id,delivered_revision,attempted_revision,attempts,error_code) select id,'ms.tasks',777,1,2,1,'TRANSIENT' from search_generations")
                .update();
        healthStatus = 503;
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSuccessfulReconciliation").value("2026-09-07T08:00:00Z"))
                .andExpect(jsonPath("$.generations[0].pendingDeliveries").value(2))
                .andExpect(jsonPath("$.generations[0].failedDeliveries").value(1))
                .andExpect(jsonPath("$.generations[0].queueLagSeconds")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(59)))
                .andExpect(jsonPath("$.generations[0].documentCount").doesNotExist());
    }

    @Test
    void aVersionOfATypeWithoutACollectionIsNeverPending() throws Exception {
        // ADR-0032, 10.3: a projection version of a type the generation has no collection for neither counts as
        // pending nor holds its activation back.
        authenticate(Set.of("*.*"), false);
        jdbc.sql("delete from search_generation_collections where entity_type = :type")
                .param("type", SearchTestEntities.ORDERS)
                .update();
        jdbc.sql("insert into search_projection_versions(entity_type,entity_id,revision) values('example.orders',5,1)")
                .update();
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generations[0].pendingDeliveries").value(0));
    }

    @Test
    void ruRegistrationIsNotReportedAsVerifiedSchemaEvidence() throws Exception {
        authenticate(Set.of("*.*"), false);
        jdbc.sql("update search_generations set schema_profile='RU'").update();
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generations[0].registeredProfile").value("RU"))
                .andExpect(jsonPath("$.generations[0].schemaMatches").value(false))
                .andExpect(jsonPath("$.lastSuccessfulReconciliation").doesNotExist());
    }

    /** The JSON path of the documents an entity's collection holds in the first generation. */
    private static String count(String code) {
        return "$.generations[0].entityDocumentCounts['" + code + "']";
    }

    /** The collection of the entity in the active generation of the fixture. */
    private static String collection(String code) {
        return ENTITIES.find(code).orElseThrow().collection("fixture_");
    }

    private void collection(UUID generation, SearchEntity entity, String name) {
        jdbc.sql("insert into search_generation_collections(generation_id,entity_type,collection)"
                        + " values(:id,:type,:collection)")
                .param("id", generation)
                .param("type", entity.code())
                .param("collection", name)
                .update();
    }
}
