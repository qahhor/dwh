package com.greenwhite.dwh.instance.search;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class SearchManagementAuthorizationTest extends SearchSettingsIntegrationTestSupport {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
    @Test void anonymousCannotObserveOrPreviewSettings() throws Exception {
        mvc.perform(get("/api/v1/search/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/search/preview").contentType("application/json").content("{\"q\":\"query\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(requests).isEmpty();
    }
    @Test void permissionsNeverDelegateUnrestrictedIndexAccessToNonAdmins() throws Exception {
        for (var grants : java.util.List.of(Set.of("platform.search.view"), Set.of("platform.settings.view"),
                Set.of("platform.search.view", "platform.settings.view", "platform.settings.update"))) {
            authenticate(grants, false);
            mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isForbidden());
            mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"query\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(auth(put("/api/v1/search/settings")).content("{}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(requests).isEmpty();
    }
    @Test void explicitEmptyLimitCannotMoveValidationAheadOfTheServiceAdminCheck() throws Exception {
        authenticate(Set.of("platform.search.view"),false);
        mvc.perform(auth(get("/api/v1/search")).param("q","delivery").param("limit",""))
                .andExpect(status().isForbidden());
        assertThat(paths).isEmpty();
    }
    @Test void searchAdminCanPreviewCurrentPolicyButCannotReadConfigurationOrSubmitDraft() throws Exception {
        authenticate(Set.of("platform.search.view"), true);
        mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk());
        mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"поставка 世界\",\"entity\":\"TASK\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeProfile").value("MIXED"))
                .andExpect(jsonPath("$.result.query").value("поставка 世界"));
        mvc.perform(auth(get("/api/v1/search/settings"))).andExpect(status().isForbidden());
        mvc.perform(auth(put("/api/v1/search/settings")).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/v1/search/preview")).content(mapper.writeValueAsString(java.util.Map.of(
                "q","query","policy",com.greenwhite.dwh.instance.search.service.SearchQueryPolicy.defaults()))))
                .andExpect(status().isForbidden());
    }
    @Test void malformedDraftsRejectBeforeAnyEngineWork() throws Exception {
        authenticate(Set.of("*.*"), false);
        for (String body : java.util.List.of("{}", "{\"q\":null}", "{\"q\":12}", "{\"q\":\"query\",\"q\":\"other\"}",
                "{\"q\":\"query\",\"collection\":\"secret\"}", "{\"q\":\"query\",\"policy\":{}}", "{\"q\":\"query\",\"policy\":null}"))
            mvc.perform(auth(post("/api/v1/search/preview")).content(body)).andExpect(status().isBadRequest());
        assertThat(requests).isEmpty();
    }

    @Test void firstSettingsReadFailureProducesSafe503BeforeEngineAndPreservesManagementReads() throws Exception {
        authenticate(Set.of("*.*"),false);
        var repository=org.mockito.Mockito.mock(com.greenwhite.dwh.instance.search.repository.SearchSettingsRepository.class);
        org.mockito.Mockito.when(repository.current()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture database unavailable"));
        var owner=context.getBean(com.greenwhite.dwh.instance.config.security.RateLimitProperties.class);
        var unavailable=new com.greenwhite.dwh.instance.search.service.SearchPolicyProvider(owner,repository);
        unavailable.refresh();
        var filter=new com.greenwhite.dwh.instance.config.security.RateLimitFilter(owner,
                context.getBean(com.greenwhite.dwh.instance.config.security.RateLimitService.class),unavailable,
                context.getBean(com.greenwhite.dwh.instance.audit.service.AuditLogService.class),
                context.getBean(com.greenwhite.dwh.instance.config.security.ProblemDetailAuthHandlers.class));
        var isolated=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                context.getBean(com.greenwhite.dwh.instance.search.controller.SearchController.class),
                context.getBean(com.greenwhite.dwh.instance.search.controller.SearchManagementController.class))
                .addFilters(context.getBean(com.greenwhite.dwh.instance.kauth.security.KauthAuthenticationFilter.class),filter)
                .addInterceptors(new com.greenwhite.dwh.instance.kauth.security.RequiresPermissionInterceptor()).build();
        org.assertj.core.api.Assertions.assertThatCode(() -> isolated.perform(auth(get("/api/v1/search")).param("q","query"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("service_unavailable")))
                .doesNotThrowAnyException();
        isolated.perform(auth(get("/api/v1/search/settings"))).andExpect(status().isOk());
        assertThat(paths).isEmpty();
    }

    @Test void settingsReadPermissionAllowsDraftsButDoesNotGrantSave() throws Exception {
        authenticate(Set.of("platform.search.view","platform.settings.view"),true);
        long version=readSettings().path("version").asLong();
        var policy=new com.greenwhite.dwh.instance.search.service.SearchQueryPolicy(3,120,20,"RU",
                com.greenwhite.dwh.instance.search.service.SearchQueryPolicy.defaults().fields());
        mvc.perform(auth(post("/api/v1/search/preview")).content(mapper.writeValueAsString(java.util.Map.of("q","delivery","entity","TASK","policy",policy))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalHits").value(3))
                .andExpect(jsonPath("$.activeProfile").value("MIXED"));
        assertThat(readSettings().path("version").asLong()).isEqualTo(version);
        assertThat(readSettings().path("policy").path("globalLimit").asInt()).isEqualTo(10);
        mvc.perform(auth(put("/api/v1/search/settings")).content(saveJson(version,policy))).andExpect(status().isForbidden());
    }

    @Test void exactIdPreviewRemainsAnIntentionalPostgresLookupDuringAnEngineOutage() throws Exception {
        authenticate(Set.of("platform.search.view"),true);
        healthStatus=503;
        mvc.perform(auth(post("/api/v1/search/preview")).content("{\"q\":\"#999999999\",\"entity\":\"TASK\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.source").value("POSTGRES"))
                .andExpect(jsonPath("$.result.degraded").value(false));
        assertThat(paths).isEmpty();
        mvc.perform(auth(get("/api/v1/search/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.dependency.healthy").value(false));
    }

    @Test void strictSearchDecodingDoesNotChangeLegacyApiNullAndUnknownFieldBehavior() throws Exception {
        authenticate(Set.of("*.*"),false);
        mvc.perform(auth(post("/api/v1/search-test/legacy")).content("{\"optional\":null,\"unusedFlag\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.optional").value(0));
        mvc.perform(auth(post("/api/v1/search-test/legacy")).content("{\"optional\":1.5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.optional").value(1));
        assertThat(paths).isEmpty();
    }

    @Test void internalServiceEntryPointsAlsoRejectDelegatedSettingsWriters() {
        var principal=new com.greenwhite.dwh.instance.common.security.SecurityContext.KauthPrincipal(actorId,"fixture","fixture@example.invalid",actorId,false,
                Set.of("platform.search.view","platform.settings.view","platform.settings.update"),1,false,0,null);
        com.greenwhite.dwh.instance.common.security.SecurityContext.setPrincipal(principal);
        try {
            var settings=context.getBean(com.greenwhite.dwh.instance.search.service.SearchSettingsService.class);
            var status=context.getBean(com.greenwhite.dwh.instance.search.service.SearchStatusService.class);
            var search=context.getBean(com.greenwhite.dwh.instance.search.service.SearchService.class);
            for (org.assertj.core.api.ThrowableAssert.ThrowingCallable call:java.util.List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                    settings::current,status::current,
                    () -> settings.save(new com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.SaveSettingsRequest(1,com.greenwhite.dwh.instance.search.service.SearchQueryPolicy.defaults())),
                    () -> search.preview(new com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.PreviewRequest("query","TASK",null))))
                org.assertj.core.api.Assertions.assertThatThrownBy(call).isInstanceOfSatisfying(com.greenwhite.dwh.instance.common.error.ApiException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(com.greenwhite.dwh.core.error.ErrorCode.FORBIDDEN));
            assertThat(paths).isEmpty();
        } finally { com.greenwhite.dwh.instance.common.security.SecurityContext.clear(); }
    }
}
