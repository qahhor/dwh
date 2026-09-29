package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.kauth.security.RequiresPermissionInterceptor;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos;
import com.smartup24.cms.instance.md.controller.MdOrgUnitController;
import com.smartup24.cms.instance.md.repository.MdOrgUnitRepository;
import com.smartup24.cms.instance.md.service.MdOrgUnitService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class MdOrgUnitControllerTest {

    @Test
    void nameOnlyPatchPreservesExistingParent() throws Exception {
        var repository = mock(MdOrgUnitRepository.class);
        var scope = mock(MdScopeService.class);
        var audit = mock(AuditLogService.class);
        when(repository.findById(3L))
                .thenReturn(Optional.of(new MdOrgUnitRepository.OrgUnitRecord(
                        3L, 2L, "CHILD", "Child", "department", "A", -10, Instant.EPOCH, Instant.EPOCH, 1L)));
        when(repository.findById(2L))
                .thenReturn(Optional.of(new MdOrgUnitRepository.OrgUnitRecord(
                        2L, null, "ROOT", "Root", "company", "A", 0, Instant.EPOCH, Instant.EPOCH, 1L)));
        var mvc = MockMvcBuilders.standaloneSetup(
                        new MdOrgUnitController(new MdOrgUnitService(repository, scope, audit), scope))
                .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
                .build();

        mvc.perform(patch("/api/v1/iam/org-units/3")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(repository).update(3L, 2L, "Renamed", "department", "A", -10, 1L);
    }

    @Test
    void blankNameAnswersWithTheCatalogKeyInTheRequestLanguage() throws Exception {
        var repository = mock(MdOrgUnitRepository.class);
        var scope = mock(MdScopeService.class);
        when(repository.findById(3L))
                .thenReturn(Optional.of(new MdOrgUnitRepository.OrgUnitRecord(
                        3L, 2L, "CHILD", "Child", "department", "A", 0, Instant.EPOCH, Instant.EPOCH, 1L)));
        var mvc = MockMvcBuilders.standaloneSetup(new MdOrgUnitController(
                        new MdOrgUnitService(repository, scope, mock(AuditLogService.class)), scope))
                .setControllerAdvice(new GlobalExceptionHandler(new PackagedProblemMessages()))
                .build();

        mvc.perform(patch("/api/v1/iam/org-units/3")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.messageKey").value("error.md.org_unit_name_required"))
                .andExpect(jsonPath("$.detail").value("Название не может быть пустым"));
        mvc.perform(patch("/api/v1/iam/org-units/3")
                        .header("If-Match", "\"1\"")
                        .header("Accept-Language", "en")
                        .contentType("application/json")
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("The name cannot be empty"));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .update(3L, 2L, "  ", "department", "A", 0, 1L);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void exposesExplicitUserAssignmentsToViewers() throws Exception {
        MdScopeService scopeService = mock(MdScopeService.class);
        SecurityContext.setPrincipal(principal(Set.of("iam.org_units.view")));
        when(scopeService.getUserAssignments(42L)).thenReturn(new MdOrgUnitDtos.UserAssignments(42L, List.of(7L), 9L));

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
        when(scopeService.getUserAssignments(42L)).thenReturn(new MdOrgUnitDtos.UserAssignments(42L, List.of(), null));

        mvc(scopeService)
                .perform(get("/api/v1/iam/org-units/users/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacyOrgUnitId").value((Object) null));
    }

    @Test
    void dtoIncludesNullLegacyOrgUnitIdUnderTheApplicationNonNullPolicy() throws Exception {
        var mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(ignored ->
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();

        String json = mapper.writeValueAsString(new MdOrgUnitDtos.UserAssignments(42L, List.of(), null));

        assertThat(json).contains("\"legacyOrgUnitId\":null");
    }

    @Test
    void exposesRoleScopeRuleToViewers() throws Exception {
        MdScopeService scopeService = mock(MdScopeService.class);
        SecurityContext.setPrincipal(principal(Set.of("iam.org_units.view")));
        when(scopeService.getRoleScopeRule(42L)).thenReturn(new MdOrgUnitDtos.RoleRule(42L, MdScopeService.RULE_ALL));

        mvc(scopeService)
                .perform(get("/api/v1/iam/org-units/roles/42/rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleId").value(42))
                .andExpect(jsonPath("$.rule").value("ALL"));
    }

    @Test
    void newReadEndpointsRejectAnonymousRequests() throws Exception {
        for (String path : List.of("/api/v1/iam/org-units/users/42", "/api/v1/iam/org-units/roles/42/rule")) {
            mvc(mock(MdScopeService.class))
                    .perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("unauthorized"));
        }
    }

    @Test
    void newReadEndpointsRejectPrincipalsWithoutViewPermission() throws Exception {
        SecurityContext.setPrincipal(principal(Set.of()));

        for (String path : List.of("/api/v1/iam/org-units/users/42", "/api/v1/iam/org-units/roles/42/rule")) {
            mvc(mock(MdScopeService.class))
                    .perform(get(path))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("permission_denied"));
        }
    }

    @Test
    void assignmentPutRejectsAnAbsentOrNullIdList() throws Exception {
        SecurityContext.setPrincipal(principal(Set.of("iam.org_units.assign")));
        MockMvc mvc = mvc(mock(MdScopeService.class));

        for (String body : List.of("{}", "{\"orgUnitIds\":null}")) {
            mvc.perform(put("/api/v1/iam/org-units/users/42")
                            .contentType("application/json")
                            .characterEncoding(StandardCharsets.UTF_8)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("validation_failed"));
        }
    }

    private static MockMvc mvc(MdScopeService scopeService) {
        return MockMvcBuilders.standaloneSetup(new MdOrgUnitController(mock(MdOrgUnitService.class), scopeService))
                .addInterceptors(new RequiresPermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
                .build();
    }

    private static SecurityContext.KauthPrincipal principal(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                10L, "org-viewer", "org-viewer@example.invalid", 20L, false, permissions, 1L, false, 0, null);
    }
}
