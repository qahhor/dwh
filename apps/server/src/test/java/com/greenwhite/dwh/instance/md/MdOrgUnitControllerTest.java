package com.greenwhite.dwh.instance.md;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.config.error.GlobalExceptionHandler;
import com.greenwhite.dwh.instance.kauth.security.RequiresPermissionInterceptor;
import com.greenwhite.dwh.instance.md.controller.MdOrgUnitController;
import com.greenwhite.dwh.instance.md.dto.MdOrgUnitDtos;
import com.greenwhite.dwh.instance.md.service.MdOrgUnitService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MdOrgUnitControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void exposesExplicitUserAssignmentsToViewers() throws Exception {
        MdScopeService scopeService = mock(MdScopeService.class);
        SecurityContext.setPrincipal(principal(Set.of("iam.org_units.view")));
        when(scopeService.getUserAssignments(42L)).thenReturn(
                new MdOrgUnitDtos.UserAssignments(42L, java.util.List.of(7L), 9L));

        mvc(scopeService)
                .perform(get("/api/v1/iam/org-units/users/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.orgUnitIds[0]").value(7))
                .andExpect(jsonPath("$.legacyOrgUnitId").value(9));
    }

    @Test
    void serializesNullLegacyOrgUnitIdInsteadOfOmittingIt() throws Exception {
        MdScopeService scopeService = mock(MdScopeService.class);
        SecurityContext.setPrincipal(principal(Set.of("iam.org_units.view")));
        when(scopeService.getUserAssignments(42L)).thenReturn(
                new MdOrgUnitDtos.UserAssignments(42L, java.util.List.of(), null));

        mvc(scopeService)
                .perform(get("/api/v1/iam/org-units/users/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacyOrgUnitId").value((Object) null));
    }

    @Test
    void dtoIncludesNullLegacyOrgUnitIdUnderTheApplicationNonNullPolicy() throws Exception {
        var mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();

        String json = mapper.writeValueAsString(
                new MdOrgUnitDtos.UserAssignments(42L, java.util.List.of(), null));

        assertThat(json).contains("\"legacyOrgUnitId\":null");
    }

    @Test
    void exposesRoleScopeRuleToViewers() throws Exception {
        MdScopeService scopeService = mock(MdScopeService.class);
        SecurityContext.setPrincipal(principal(Set.of("iam.org_units.view")));
        when(scopeService.getRoleScopeRule(42L)).thenReturn(
                new MdOrgUnitDtos.RoleRule(42L, MdScopeService.RULE_ALL));

        mvc(scopeService)
                .perform(get("/api/v1/iam/org-units/roles/42/rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleId").value(42))
                .andExpect(jsonPath("$.rule").value("ALL"));
    }

    @Test
    void newReadEndpointsRejectAnonymousRequests() throws Exception {
        for (String path : java.util.List.of(
                "/api/v1/iam/org-units/users/42",
                "/api/v1/iam/org-units/roles/42/rule")) {
            mvc(mock(MdScopeService.class)).perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("unauthorized"));
        }
    }

    @Test
    void newReadEndpointsRejectPrincipalsWithoutViewPermission() throws Exception {
        SecurityContext.setPrincipal(principal(Set.of()));

        for (String path : java.util.List.of(
                "/api/v1/iam/org-units/users/42",
                "/api/v1/iam/org-units/roles/42/rule")) {
            mvc(mock(MdScopeService.class)).perform(get(path))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("permission_denied"));
        }
    }

    @Test
    void assignmentPutRejectsAnAbsentOrNullIdList() throws Exception {
        SecurityContext.setPrincipal(principal(Set.of("iam.org_units.assign")));
        MockMvc mvc = mvc(mock(MdScopeService.class));

        for (String body : java.util.List.of("{}", "{\"orgUnitIds\":null}")) {
            mvc.perform(put("/api/v1/iam/org-units/users/42")
                            .contentType("application/json")
                            .characterEncoding(StandardCharsets.UTF_8)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("validation_failed"));
        }
    }

    private static MockMvc mvc(MdScopeService scopeService) {
        return MockMvcBuilders.standaloneSetup(
                        new MdOrgUnitController(mock(MdOrgUnitService.class), scopeService))
                .addInterceptors(new RequiresPermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static SecurityContext.KauthPrincipal principal(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                10L, "org-viewer", "org-viewer@example.invalid", 20L, false,
                permissions, 1L, false, 0, null);
    }
}
