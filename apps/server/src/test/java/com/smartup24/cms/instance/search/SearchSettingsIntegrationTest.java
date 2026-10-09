package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartup24.cms.instance.audit.service.*;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.security.*;
import com.smartup24.cms.instance.kauth.service.*;
import com.smartup24.cms.instance.md.service.*;
import com.smartup24.cms.instance.search.api.SearchManagementDtos;
import com.smartup24.cms.instance.search.service.*;
import com.smartup24.cms.instance.search.service.SearchSettingsService;
import com.smartup24.cms.instance.search.typesense.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

class SearchSettingsIntegrationTest extends SearchSettingsIntegrationTestSupport {
    @Autowired
    SearchSettingsService settings;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void savedLimitAboveTenReachesTheRealControllerAndEngineConsumer() throws Exception {
        authenticate(Set.of("*.*"), false);
        var current = readSettings();
        var policy = mapper.valueToTree(defaults()).deepCopy();
        ((ObjectNode) policy).put("globalLimit", 14);
        mvc.perform(auth(put("/api/v1/search/settings"))
                        .content(saveJson(current.path("version").asLong(), policy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.globalLimit").value(14));
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery").param("entity", "ms.tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(14));
        // The index is asked for twice the hits: the database check may drop candidates (ADR-0032, 10.3).
        assertThat(requests.getLast()).contains("\"per_page\":28");
        mvc.perform(auth(put("/api/v1/search/settings"))
                        .content(saveJson(current.path("version").asLong(), policy)))
                .andExpect(status().isConflict());
        assertThat(readSettings().path("policy").path("globalLimit").asInt()).isEqualTo(14);
        assertThat(jdbc.sql("select count(*) from audit_log where table_name='search_settings' and changed_by=:actor")
                        .param("actor", actorId)
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void invalidPolicyNeverPersistsOrReachesTheEngine() throws Exception {
        authenticate(Set.of("*.*"), false);
        var current = readSettings();
        for (String mutation : List.of(
                "\"globalLimit\":0",
                "\"globalLimit\":51",
                "\"globalLimit\":null",
                "\"globalLimit\":1.5",
                "\"globalLimit\":\"12\"")) {
            String policy = mapper.writeValueAsString(defaults()).replace("\"globalLimit\":10", mutation);
            mvc.perform(auth(put("/api/v1/search/settings"))
                            .content(
                                    "{\"version\":" + current.path("version").asLong() + ",\"policy\":" + policy + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(readSettings().path("version").asLong())
                .isEqualTo(current.path("version").asLong());
        assertThat(requests).isEmpty();
    }

    @Test
    void brokenPolicyRuleNamesItselfInTheProblem() throws Exception {
        authenticate(Set.of("*.*"), false);
        long version = readSettings().path("version").asLong();
        var limit = (ObjectNode) mapper.valueToTree(defaults());
        limit.put("globalLimit", 51);
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(version, limit)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.messageKey").value("error.search.global_limit_range"))
                .andExpect(jsonPath("$.detail").value("Лимит глобального поиска должен быть от 1 до 50"));
        var weight = (ObjectNode) mapper.valueToTree(defaults());
        ((ObjectNode) weight.path("fields").path("ms.tasks").get(0)).put("weight", 200);
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(version, weight)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("error.search.field_weight_range"));
        var profile = (ObjectNode) mapper.valueToTree(defaults());
        profile.put("schemaProfile", "EN");
        mvc.perform(auth(post("/api/v1/search/preview"))
                        .content(mapper.writeValueAsString(Map.of("q", "delivery", "policy", profile))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("error.search.schema_profile_invalid"));
        mvc.perform(auth(put("/api/v1/search/settings")).content("{\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("error.search.settings_request_invalid"));
        assertThat(readSettings().path("version").asLong()).isEqualTo(version);
        assertThat(requests).isEmpty();
    }

    @Test
    void zeroWeightsAreOmittedWhileLiveWeightTypoAndPrefixChangesReachTheEngine() throws Exception {
        authenticate(Set.of("*.*"), false);
        var current = readSettings();
        var policy = (ObjectNode) mapper.valueToTree(defaults());
        var fields = policy.path("fields").path("ms.tasks");
        ((ObjectNode) fields.get(0)).put("weight", 5).put("numTypos", 1).put("prefix", false);
        ((ObjectNode) fields.get(1)).put("weight", 0);
        mvc.perform(auth(put("/api/v1/search/settings"))
                        .content(saveJson(current.path("version").asLong(), policy)))
                .andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery").param("entity", "ms.tasks"))
                .andExpect(status().isOk());
        var emitted = mapper.readTree(requests.getLast()).path("searches").get(0);
        // The fields of the tasks' search spec (ADR-0032, 10.3): the title and the text; the text weighs nothing here.
        assertThat(emitted.path("query_by").asString()).isEqualTo("title");
        assertThat(emitted.path("query_by_weights").asString()).isEqualTo("5");
        assertThat(emitted.path("num_typos").asString()).isEqualTo("1");
        assertThat(emitted.path("prefix").asString()).isEqualTo("false");
    }

    @Test
    void rollbackNeverPublishesAnUncommittedPolicyOrAuditRow() {
        principal();
        try {
            var before = settings.current();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                settings.save(new SearchManagementDtos.SaveSettingsRequest(before.version(), withLimit(4)));
                assertThat(provider.current().globalLimit()).isEqualTo(10);
                tx.setRollbackOnly();
            });
            assertThat(settings.current()).isEqualTo(before);
            assertThat(provider.current().globalLimit()).isEqualTo(10);
            assertThat(auditCount()).isZero();
        } finally {
            SecurityContext.clear();
        }
    }

    @Test
    void databaseCommitFailureAfterTheSaveBodyDoesNotPublishThePolicy() {
        principal();
        var before = settings.current();
        jdbc.sql(
                        "create function fixture_reject_settings_commit() returns trigger language plpgsql as 'begin raise exception ''fixture commit rejected''; end'")
                .update();
        jdbc.sql(
                        "create constraint trigger fixture_settings_commit after update on search_settings deferrable initially deferred for each row execute function fixture_reject_settings_commit()")
                .update();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            settings.save(new SearchManagementDtos.SaveSettingsRequest(before.version(), withLimit(4))))
                    .isInstanceOf(TransactionException.class);
            assertThat(settings.current()).isEqualTo(before);
            assertThat(provider.current().globalLimit()).isEqualTo(10);
            assertThat(auditCount()).isZero();
        } finally {
            jdbc.sql("drop trigger fixture_settings_commit on search_settings").update();
            jdbc.sql("drop function fixture_reject_settings_commit()").update();
            SecurityContext.clear();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidPolicies")
    void strictPolicyWhitelistRejectsMalformedOrUnusedConfiguration(String policy) throws Exception {
        authenticate(Set.of("*.*"), false);
        long version = readSettings().path("version").asLong();
        mvc.perform(auth(put("/api/v1/search/settings"))
                        .content("{\"version\":" + version + ",\"policy\":" + policy + "}"))
                .andExpect(status().isBadRequest());
        assertThat(readSettings().path("version").asLong()).isEqualTo(version);
        assertThat(auditCount()).isZero();
        assertThat(paths).isEmpty();
    }

    static Stream<String> invalidPolicies() {
        String policy = mapper.writeValueAsString(defaults());
        var invalid = new ArrayList<String>();
        for (String replacement : List.of("\"requestsPerMinute\":29", "\"requestsPerMinute\":601"))
            invalid.add(policy.replace("\"requestsPerMinute\":120", replacement));
        for (String replacement : List.of("\"burst\":9", "\"burst\":61"))
            invalid.add(policy.replace("\"burst\":20", replacement));
        invalid.add(policy.replace("\"requestsPerMinute\":120", "\"requestsPerMinute\":30")
                .replace("\"burst\":20", "\"burst\":31"));
        for (String replacement :
                List.of("\"schemaProfile\":null", "\"schemaProfile\":\"ru\"", "\"schemaProfile\":\"OTHER\""))
            invalid.add(policy.replace("\"schemaProfile\":\"MIXED\"", replacement));
        for (String replacement :
                List.of("\"weight\":-1", "\"weight\":128", "\"weight\":0.5", "\"weight\":null", "\"weight\":\"10\""))
            invalid.add(policy.replaceFirst("\"weight\":10", replacement));
        invalid.add(policy.replaceFirst("\"weight\":10,", ""));
        for (String replacement : List.of("\"numTypos\":-1", "\"numTypos\":3", "\"numTypos\":1.5", "\"numTypos\":null"))
            invalid.add(policy.replaceFirst("\"numTypos\":2", replacement));
        invalid.add(policy.replaceFirst("\"prefix\":true", "\"prefix\":null"));
        invalid.add(policy.replaceFirst("\"prefix\":true", "\"prefix\":\"true\""));
        invalid.add(policy.replaceFirst("\"prefix\":true", "\"prefix\":true,\"unused\":true"));
        invalid.add(policy.replaceFirst("\"field\":\"title\"", "\"field\":\"private_notes\""));
        invalid.add(policy.replaceFirst("\"field\":\"title\"", "\"field\":\"contentMd\""));
        invalid.add(policy.replaceFirst("\"ms.tasks\":", "\"other.entity\":"));
        invalid.add(policy.replaceFirst("\"globalLimit\":10,", ""));
        invalid.add(policy.replaceFirst("\"globalLimit\":10", "\"globalLimit\":10,\"arbitraryOption\":true"));
        invalid.add(policy.replaceAll("\"weight\":[0-9]+", "\"weight\":0"));
        return invalid.stream();
    }

    @Test
    void strictSaveEnvelopeRejectsMissingNullFractionalAndDuplicateVersion() throws Exception {
        authenticate(Set.of("*.*"), false);
        String policy = mapper.writeValueAsString(defaults());
        long before = readSettings().path("version").asLong();
        for (String body : List.of(
                "null",
                "{}",
                "{\"version\":1}",
                "{\"policy\":" + policy + "}",
                "{\"version\":null,\"policy\":" + policy + "}",
                "{\"version\":1.5,\"policy\":" + policy + "}",
                "{\"version\":0,\"policy\":" + policy + "}",
                "{\"version\":1,\"version\":2,\"policy\":" + policy + "}",
                "{\"version\":1,\"policy\":null}",
                "{\"version\":1,\"policy\":" + policy + ",\"url\":\"unused\"}"))
            mvc.perform(auth(put("/api/v1/search/settings")).content(body)).andExpect(status().isBadRequest());
        assertThat(readSettings().path("version").asLong()).isEqualTo(before);
        assertThat(paths).isEmpty();
    }

    @Test
    void savedRateAndBurstApplyToInteractivePreviewBeforeEngineWork() throws Exception {
        authenticate(Set.of("*.*"), false);
        long version = readSettings().path("version").asLong();
        var policy = new SearchQueryPolicy(10, 30, 10, "MIXED", defaults().fields());
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(version, policy)))
                .andExpect(status().isOk());
        for (int i = 0; i < 10; i++)
            mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"delivery\",\"entity\":\"ms.tasks\"}"))
                    .andExpect(status().isOk());
        mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"delivery\",\"entity\":\"ms.tasks\"}"))
                .andExpect(status().isTooManyRequests());
        assertThat(paths).hasSize(10);
        mvc.perform(auth(get("/api/v1/search/settings"))).andExpect(status().isOk());
        assertThat(provider.effectiveBudgets().user().perMinute()).isEqualTo(30);
        assertThat(provider.effectiveBudgets().user().capacity()).isEqualTo(10);
    }

    @Test
    void corruptStoredSettingsStayExplicitAndFailedRefreshRetainsTheLastValidRate() throws Exception {
        authenticate(Set.of("*.*"), false);
        long version = readSettings().path("version").asLong();
        var policy = new SearchQueryPolicy(4, 30, 10, "MIXED", defaults().fields());
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(version, policy)))
                .andExpect(status().isOk());
        jdbc.sql("update search_settings set configuration='{\"globalLimit\":50}' where id=1")
                .update();
        provider.refresh();
        assertThat(provider.degraded()).isTrue();
        assertThat(provider.current().globalLimit()).isEqualTo(4);
        assertThat(provider.effectiveBudgets().user().perMinute()).isEqualTo(30);
        mvc.perform(auth(get("/api/v1/search/settings"))).andExpect(status().isServiceUnavailable());
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery")).andExpect(status().isServiceUnavailable());
        assertThat(paths).isEmpty();
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingsDegraded").value(true));
    }

    @Test
    void delayedEarlierCommitCannotReplaceTheNewerCommittedPolicy() throws Exception {
        principal();
        long version = settings.current().version();
        SecurityContext.clear();
        var committed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> {
                principal();
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            public void afterCommit() {
                                committed.countDown();
                                await(release);
                            }
                        });
                        settings.save(new SearchManagementDtos.SaveSettingsRequest(version, withLimit(4)));
                    });
                } finally {
                    SecurityContext.clear();
                }
            });
            try {
                assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
                principal();
                var saved = settings.save(new SearchManagementDtos.SaveSettingsRequest(version + 1, withLimit(5)));
                assertThat(saved.version()).isEqualTo(version + 2);
                assertThat(provider.current().globalLimit()).isEqualTo(5);
            } finally {
                release.countDown();
                SecurityContext.clear();
            }
            first.get(10, TimeUnit.SECONDS);
            assertThat(provider.current().globalLimit()).isEqualTo(5);
            assertThat(provider.snapshot().version()).isEqualTo(version + 2);
        }
    }

    @Test
    void executionUsesOneCommittedGenerationAndPolicySnapshotAndReleasesItsConnectionBeforeHttp() throws Exception {
        authenticate(Set.of("*.*"), false);
        long version = readSettings().path("version").asLong();
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(version, withLimit(14))))
                .andExpect(status().isOk());
        var source = (ObservingDataSource) contextSource;
        Thread caller = Thread.currentThread();
        var networkConnections = new AtomicInteger(-1);
        beforeEngine = () -> networkConnections.set(source.openConnections(caller));
        source.observe(
                caller,
                () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.sql(
                                    "update search_settings set version=version+1,configuration=cast(:policy as jsonb) where id=1")
                            .param("policy", mapper.writeValueAsString(withLimit(5)))
                            .update();
                    jdbc.sql("update search_generation_collections set collection='next_entity_ms_tasks'"
                                    + " where entity_type='ms.tasks'")
                            .update();
                }));
        try {
            mvc.perform(auth(get("/api/v1/search")).param("q", "delivery").param("entity", "ms.tasks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalHits").value(14));
            var emitted = mapper.readTree(requests.getLast()).path("searches").get(0);
            assertThat(emitted.path("collection").asString()).isEqualTo("fixture_entity_ms_tasks");
            assertThat(emitted.path("per_page").asInt()).isEqualTo(28);
            assertThat(source.snapshotReads.get()).isEqualTo(1);
            assertThat(networkConnections).hasValue(0);
        } finally {
            source.stopObserving();
            beforeEngine = () -> {};
        }
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery").param("entity", "ms.tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(5));
        assertThat(mapper.readTree(requests.getLast())
                        .path("searches")
                        .get(0)
                        .path("collection")
                        .asString())
                .isEqualTo("next_entity_ms_tasks");
    }

    @Test
    void genericPerUserSettingsCannotOverrideTheInstanceSearchPolicy() throws Exception {
        authenticate(Set.of("*.*"), false);
        long user = jdbc.sql(
                        "insert into md_users(name,login,email,password_hash,state,language,timezone) values('Search fixture',:login,:email,'x','A','ru','UTC') returning id")
                .param("login", "search-fixture-" + actorId)
                .param("email", "search-fixture-" + actorId + "@example.invalid")
                .query(Long.class)
                .single();
        jdbc.sql(
                        "insert into md_settings(user_id,key,value) values(:user,'search',:policy),(:user,'platform.search',:policy),(:user,'search.globalLimit','50')")
                .param("user", user)
                .param("policy", mapper.writeValueAsString(withLimit(50)))
                .update();
        actorId = user;
        authenticate(Set.of("*.*"), false);
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery").param("entity", "ms.tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(10));
        assertThat(readSettings().path("policy").path("globalLimit").asInt()).isEqualTo(10);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "0", "51", "1.5", "null"})
    void invalidExplicitLimitsNeverBecomeTheSavedDefault(String value) throws Exception {
        authenticate(Set.of("*.*"), false);
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery").param("limit", value))
                .andExpect(status().isBadRequest());
        assertThat(paths).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture latch timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void principal() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                actorId, "fixture", "fixture@example.invalid", actorId, false, Set.of("*.*"), 1, false, 0, null));
    }

    private long auditCount() {
        return jdbc.sql("select count(*) from audit_log where table_name='search_settings' and changed_by=:actor")
                .param("actor", actorId)
                .query(Long.class)
                .single();
    }

    private static SearchQueryPolicy withLimit(int limit) {
        return new SearchQueryPolicy(limit, 120, 20, "MIXED", defaults().fields());
    }
}
