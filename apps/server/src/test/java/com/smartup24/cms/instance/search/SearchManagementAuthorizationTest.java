package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.security.ProblemDetailAuthHandlers;
import com.smartup24.cms.instance.config.security.RateLimitFilter;
import com.smartup24.cms.instance.config.security.RateLimitProperties;
import com.smartup24.cms.instance.config.security.RateLimitService;
import com.smartup24.cms.instance.kauth.security.KauthAuthenticationFilter;
import com.smartup24.cms.instance.kauth.security.RequiresPermissionInterceptor;
import com.smartup24.cms.instance.search.controller.SearchController;
import com.smartup24.cms.instance.search.controller.SearchManagementController;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.service.SearchSettingsService;
import com.smartup24.cms.instance.search.service.SearchStatusService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SearchManagementAuthorizationTest extends SearchSettingsIntegrationTestSupport {
    @Autowired
    ApplicationContext context;

    @Test
    void anonymousCannotObserveOrPreviewSettings() throws Exception {
        mvc.perform(get("/api/v1/search/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/search/preview")
                        .contentType("application/json")
                        .content("{\"q\":\"query\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(requests).isEmpty();
    }

    @Test
    void permissionsNeverDelegateUnrestrictedIndexAccessToNonAdmins() throws Exception {
        for (var grants : List.of(
                Set.of("search.view"),
                Set.of("md.settings.view"),
                Set.of("search.view", "md.settings.view", "md.settings.update"))) {
            authenticate(grants, false);
            mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isForbidden());
            mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"query\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(auth(put("/api/v1/search/settings")).content("{}")).andExpect(status().isForbidden());
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void administratorWithRestrictedDataScopeFindsOnlyTheRowsOfTheScopeAndStillManagesTheIndex() throws Exception {
        // ADR-0013, 2.5 as ADR-0032, 19 (question 8) changes it: the search answers in the caller's own scope. A rule
        // UNITS without a unit sees no task, so no query reaches the engine for them; status and jobs carry no rows.
        authenticate(Set.of("search.view", "tasks.items.view"), true);
        jdbc.sql("insert into md_user_scope (user_id, rule) values (:user, 'UNITS')"
                        + " on conflict (user_id) do update set rule = excluded.rule")
                .param("user", actorId)
                .update();
        try {
            mvc.perform(auth(get("/api/v1/search")).param("q", "delivery"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalHits").value(0));
            mvc.perform(auth(get("/api/v1/search")).param("q", "#1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalHits").value(0));
            mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"delivery\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.totalHits").value(0));
            assertThat(paths).noneMatch(path -> path.equals("POST /multi_search"));
            mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk());
            mvc.perform(auth(get("/api/v1/search/jobs"))).andExpect(status().isOk());
        } finally {
            jdbc.sql("delete from md_user_scope where user_id = :user")
                    .param("user", actorId)
                    .update();
        }
    }

    @Test
    void aViewerSearchesOnlyTheEntitiesTheyMayViewEachCheckedInTheDatabase() throws Exception {
        // ADR-0032, 10.3: anyone with the search right searches the entities whose view right they hold; the index
        // query names only those collections, and every hit is checked again in the database.
        authenticate(Set.of("search.view", "tasks.items.view"), false);
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].entityType").value("ms.tasks"))
                .andExpect(jsonPath("$.hits[0].targetUrl").value("/tasks/items/1"));
        assertThat(requests.stream().filter(body -> body.contains("searches")).toList())
                .singleElement()
                .satisfies(body -> {
                    var searches = mapper.readTree(body).path("searches");
                    assertThat(searches).hasSize(1);
                    assertThat(searches.get(0).path("collection").asText())
                            .isEqualTo(ENTITIES.find("ms.tasks").orElseThrow().collection("fixture_"));
                    // The rule ALL restricts no task: the query carries no scope filter.
                    assertThat(searches.get(0).has("filter_by")).isFalse();
                });
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery").param("entity", "md.users"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("error.search.category_unknown"));
        mvc.perform(auth(get("/api/v1/search/entities")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("ms.tasks"));
    }

    @Test
    void aStaleIndexHitThatTheDatabaseNoLongerFindsIsNeverAnswered() throws Exception {
        // ADR-0032, 10.3: the engine answers ids 1..n; a task that is gone is dropped by the database check.
        authenticate(Set.of("search.view", "tasks.items.view"), false);
        jdbc.sql("delete from ms_tasks where id = 1").update();
        try {
            mvc.perform(auth(get("/api/v1/search"))
                            .param("q", "delivery")
                            .param("entity", "ms.tasks")
                            .param("limit", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalHits").value(3))
                    .andExpect(jsonPath("$.hits[*].id").value(org.hamcrest.Matchers.contains("2", "3", "4")));
        } finally {
            jdbc.sql("""
                            insert into ms_tasks (id, title, priority, reporter_id, created_by, modified_by)
                            overriding system value values (1, 'Delivery 1', 'medium', :actor, :actor, :actor)
                            on conflict (id) do nothing
                            """).param("actor", actorId).update();
        }
    }

    @Test
    void unrestrictedAdministratorSearches() throws Exception {
        authenticate(Set.of("search.view"), true);
        jdbc.sql("insert into md_user_scope (user_id, rule) values (:user, 'ALL')"
                        + " on conflict (user_id) do update set rule = excluded.rule")
                .param("user", actorId)
                .update();
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery")).andExpect(status().isOk());
    }

    @Test
    void explicitEmptyLimitCannotMoveValidationAheadOfTheAccessCheck() throws Exception {
        authenticate(Set.of("tasks.items.view"), false);
        mvc.perform(auth(get("/api/v1/search")).param("q", "delivery").param("limit", ""))
                .andExpect(status().isForbidden());
        assertThat(paths).isEmpty();
    }

    @Test
    void searchAdminCanPreviewCurrentPolicyButCannotReadConfigurationOrSubmitDraft() throws Exception {
        authenticate(Set.of("search.view", "tasks.items.view"), true);
        mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk());
        mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"поставка 世界\",\"entity\":\"ms.tasks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProfile").value("MIXED"))
                .andExpect(jsonPath("$.result.query").value("поставка 世界"));
        mvc.perform(auth(get("/api/v1/search/settings"))).andExpect(status().isForbidden());
        mvc.perform(auth(put("/api/v1/search/settings")).content("{}")).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/v1/search/preview"))
                        .content(mapper.writeValueAsString(
                                Map.of("q", "query", "policy", SearchQueryPolicy.defaults()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void malformedDraftsRejectBeforeAnyEngineWork() throws Exception {
        authenticate(Set.of("*.*"), false);
        for (String body : List.of(
                "{}",
                "{\"q\":null}",
                "{\"q\":12}",
                "{\"q\":\"query\",\"q\":\"other\"}",
                "{\"q\":\"query\",\"collection\":\"secret\"}",
                "{\"q\":\"query\",\"policy\":{}}",
                "{\"q\":\"query\",\"policy\":null}"))
            mvc.perform(auth(post("/api/v1/search/preview")).content(body)).andExpect(status().isBadRequest());
        assertThat(requests).isEmpty();
    }

    @Test
    void firstSettingsReadFailureProducesSafe503BeforeEngineAndPreservesManagementReads() throws Exception {
        authenticate(Set.of("*.*"), false);
        var repository = org.mockito.Mockito.mock(SearchSettingsRepository.class);
        org.mockito.Mockito.when(repository.current())
                .thenThrow(new DataAccessResourceFailureException("fixture database unavailable"));
        var owner = context.getBean(RateLimitProperties.class);
        var unavailable = new SearchPolicyProvider(owner, repository);
        unavailable.refresh();
        var filter = new RateLimitFilter(
                owner,
                context.getBean(RateLimitService.class),
                unavailable,
                context.getBean(AuditLogService.class),
                context.getBean(ProblemDetailAuthHandlers.class));
        var isolated = MockMvcBuilders.standaloneSetup(
                        context.getBean(SearchController.class), context.getBean(SearchManagementController.class))
                .addFilters(context.getBean(KauthAuthenticationFilter.class), filter)
                .addInterceptors(new RequiresPermissionInterceptor())
                .build();
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> isolated.perform(auth(get("/api/v1/search")).param("q", "query"))
                                .andExpect(status().isServiceUnavailable())
                                .andExpect(jsonPath("$.code").value("service_unavailable")))
                .doesNotThrowAnyException();
        isolated.perform(auth(get("/api/v1/search/settings"))).andExpect(status().isOk());
        assertThat(paths).isEmpty();
    }

    @Test
    void settingsReadPermissionAllowsDraftsButDoesNotGrantSave() throws Exception {
        authenticate(Set.of("search.view", "md.settings.view", "tasks.items.view"), true);
        long version = readSettings().path("version").asLong();
        var policy = new SearchQueryPolicy(
                3, 120, 20, "RU", SearchQueryPolicy.defaults().fields());
        mvc.perform(auth(post("/api/v1/search/preview"))
                        .content(mapper.writeValueAsString(
                                Map.of("q", "delivery", "entity", "ms.tasks", "policy", policy))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalHits").value(3))
                .andExpect(jsonPath("$.activeProfile").value("MIXED"));
        assertThat(readSettings().path("version").asLong()).isEqualTo(version);
        assertThat(readSettings().path("policy").path("globalLimit").asInt()).isEqualTo(10);
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(version, policy)))
                .andExpect(status().isForbidden());
    }

    @Test
    void exactIdPreviewRemainsAnIntentionalPostgresLookupDuringAnEngineOutage() throws Exception {
        authenticate(Set.of("search.view", "tasks.items.view"), true);
        healthStatus = 503;
        mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"#999999999\",\"entity\":\"ms.tasks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.source").value("POSTGRES"))
                .andExpect(jsonPath("$.result.degraded").value(false));
        assertThat(paths).isEmpty();
        mvc.perform(auth(get("/api/v1/search/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependency.healthy").value(false));
    }

    @Test
    void strictSearchDecodingDoesNotChangeLegacyApiNullAndUnknownFieldBehavior() throws Exception {
        authenticate(Set.of("*.*"), false);
        mvc.perform(auth(post("/api/v1/search-test/legacy")).content("{\"optional\":null,\"unusedFlag\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optional").value(0));
        mvc.perform(auth(post("/api/v1/search-test/legacy")).content("{\"optional\":1.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optional").value(1));
        assertThat(paths).isEmpty();
    }

    @Test
    void internalServiceEntryPointsAlsoRejectDelegatedSettingsWriters() {
        var principal = new SecurityContext.KauthPrincipal(
                actorId,
                "fixture",
                "fixture@example.invalid",
                actorId,
                false,
                Set.of("search.view", "md.settings.view", "md.settings.update"),
                1,
                false,
                0,
                null);
        SecurityContext.setPrincipal(principal);
        try {
            var settings = context.getBean(SearchSettingsService.class);
            var status = context.getBean(SearchStatusService.class);
            var search = context.getBean(SearchService.class);
            for (org.assertj.core.api.ThrowableAssert.ThrowingCallable call :
                    List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                            settings::current,
                            status::current,
                            () -> settings.save(
                                    new SearchManagementDtos.SaveSettingsRequest(1, SearchQueryPolicy.defaults())),
                            () -> search.preview(new SearchManagementDtos.PreviewRequest("query", "ms.tasks", null))))
                org.assertj.core.api.Assertions.assertThatThrownBy(call)
                        .isInstanceOfSatisfying(
                                ApiException.class,
                                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(paths).isEmpty();
        } finally {
            SecurityContext.clear();
        }
    }
}
