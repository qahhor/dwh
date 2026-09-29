package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos;
import com.smartup24.cms.instance.md.api.MdUserDtos.UserListFilters;
import com.smartup24.cms.instance.md.controller.MdAssignmentController;
import com.smartup24.cms.instance.md.controller.MdCustomFieldController;
import com.smartup24.cms.instance.md.controller.MdOrgUnitController;
import com.smartup24.cms.instance.md.controller.MdRoleController;
import com.smartup24.cms.instance.md.controller.MdUserController;
import com.smartup24.cms.instance.md.repository.MdCustomFieldRepository;
import com.smartup24.cms.instance.md.repository.MdOrgUnitRepository;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.md.service.MdAssignmentService;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.md.service.MdOrgUnitService;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdRoleService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserListService;
import com.smartup24.cms.instance.md.service.MdUserSecurityService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.MdUserView;
import com.smartup24.cms.instance.md.service.PasswordHasher;
import com.smartup24.cms.instance.md.service.PasswordValidator;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan 10/10, item 3.2: the IAM and settings screens read these answers, so their property sets are pinned here.
 * Controllers run over the real services and mocked repositories, so the service mapping is covered too; the mapper
 * mirrors {@code spring.jackson} in application.yml (null properties are left out).
 */
class MdIamWireFormatTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    ignored -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
    private static final Instant AT = Instant.parse("2026-09-01T10:00:00Z");

    private static final Set<String> ROLE =
            Set.of("id", "name", "pcode", "state", "orderNo", "createdAt", "modifiedAt");
    private static final Set<String> ORG_UNIT =
            Set.of("id", "parentId", "code", "name", "kind", "state", "orderNo", "createdAt", "modifiedAt");
    private static final Set<String> CUSTOM_FIELD = Set.of(
            "id",
            "entityType",
            "code",
            "name",
            "fieldType",
            "isRequired",
            "defaultValue",
            "optionsJson",
            "orderNo",
            "createdAt");
    private static final Set<String> USER = Set.of(
            "id",
            "name",
            "login",
            "email",
            "phone",
            "state",
            "managerId",
            "language",
            "timezone",
            "avatarFileId",
            "attributes",
            "is2faEnabled",
            "forcePasswordChange",
            "roleIds",
            "createdAt",
            "modifiedAt");

    private final MdRoleRepository roles = mock(MdRoleRepository.class);
    private final MdPermissionRepository permissions = mock(MdPermissionRepository.class);
    private final MdUserRepository users = mock(MdUserRepository.class);
    private final MdPermissionService permissionService = mock(MdPermissionService.class);
    private final MdScopeService scope = mock(MdScopeService.class);
    private final AuditLogService audit = mock(AuditLogService.class);

    @Test
    @DisplayName("roles: list and create answer with the role properties, the form catalog with its own")
    void roles() throws Exception {
        var record = new MdRoleRepository.RoleRecord(3L, "Менеджер", "manager", "A", 10, AT, AT);
        when(roles.listRoles()).thenReturn(List.of(record));
        when(roles.create("Новая", null, "A", 5)).thenReturn(record);
        when(permissionService.getFormCatalog())
                .thenReturn(List.of(new MdPermissionRepository.FormTreeItem(
                        "iam.users", "iam", "Пользователи", "block", "Блокировка", false)));
        when(permissionService.getFormCatalogItems()).thenCallRealMethod();
        var roleService = new MdRoleService(roles, permissionService, audit, mock(MdScopeRepository.class));
        MockMvc mvc = mvc(new MdRoleController(roleService, permissionService));

        assertThat(keys(json(mvc, get("/api/v1/iam/roles"), 200).get(0))).isEqualTo(ROLE);
        assertThat(keys(json(mvc, post("/api/v1/iam/roles").content("{\"name\":\"Новая\",\"orderNo\":5}"), 201)))
                .isEqualTo(ROLE);
        assertThat(keys(json(mvc, get("/api/v1/iam/forms"), 200).get(0)))
                .isEqualTo(Set.of("formCode", "module", "formName", "action", "actionName", "isDeprecated"));
    }

    @Test
    @DisplayName("role matrix: the request items are read as formCode and action")
    void rolePermissionsRequest() throws Exception {
        when(roles.findById(3L))
                .thenReturn(Optional.of(new MdRoleRepository.RoleRecord(3L, "Менеджер", null, "A", 10, AT, AT)));
        when(permissionService.getGrantablePairs()).thenReturn(Set.of("audit.log.view"));
        var roleService = new MdRoleService(roles, permissionService, audit, mock(MdScopeRepository.class));
        MockMvc mvc = mvc(new MdRoleController(roleService, permissionService));

        send(mvc, put("/api/v1/iam/roles/3/permissions").content("[{\"formCode\":\"audit.log\",\"action\":\"view\"}]"))
                .andStatus(204);

        verify(roles).replaceRolePermissions(3L, List.of(new MdRoleRepository.PermissionPair("audit.log", "view")));
    }

    @Test
    @DisplayName("assignments: role ids, personal grants, effective permissions and the new permissions version")
    void assignments() throws Exception {
        when(users.findById(42L)).thenReturn(Optional.of(user(42L)));
        when(roles.getUserRoleIds(42L)).thenReturn(List.of(3L));
        when(roles.findById(3L))
                .thenReturn(Optional.of(new MdRoleRepository.RoleRecord(3L, "Менеджер", null, "A", 10, AT, AT)));
        when(permissions.getEffectivePermissionsWithSource(42L))
                .thenReturn(List.of(
                        new MdPermissionRepository.EffectivePermissionItem("audit.log", "view", "personal"),
                        new MdPermissionRepository.EffectivePermissionItem("tasks.items", "create", "role:Менеджер")));
        when(permissionService.getGrantablePairs()).thenReturn(Set.of("audit.log.view"));
        when(permissionService.getPermissionVersion(42L)).thenReturn(7L);
        MockMvc mvc = mvc(new MdAssignmentController(
                new MdAssignmentService(users, roles, permissions, permissionService, scope, audit)));
        String base = "/api/v1/iam/users/42";

        JsonNode roleIds = json(mvc, get(base + "/roles"), 200);
        assertThat(keys(roleIds)).containsExactly("roleIds");
        assertThat(roleIds.get("roleIds").get(0).asLong()).isEqualTo(3L);

        JsonNode grants = json(mvc, get(base + "/permissions"), 200);
        assertThat(keys(grants)).containsExactly("grants");
        assertThat(grants.get("grants")).hasSize(1);
        assertThat(keys(grants.get("grants").get(0))).isEqualTo(Set.of("form", "action"));

        JsonNode effective = json(mvc, get(base + "/effective-permissions"), 200);
        assertThat(keys(effective)).containsExactly("items");
        assertThat(effective.get("items")).hasSize(2);
        assertThat(keys(effective.get("items").get(1))).isEqualTo(Set.of("form", "action", "source"));

        JsonNode assigned = json(mvc, put(base + "/roles").content("{\"roleIds\":[3]}"), 200);
        assertThat(keys(assigned)).containsExactly("permissionsVersion");
        assertThat(assigned.get("permissionsVersion").asLong()).isEqualTo(7L);

        JsonNode replaced = json(
                mvc,
                put(base + "/permissions").content("{\"grants\":[{\"form\":\"audit.log\",\"action\":\"view\"}]}"),
                200);
        assertThat(keys(replaced)).containsExactly("permissionsVersion");
        verify(permissions)
                .replaceUserPermissions(42L, List.of(new MdRoleRepository.PermissionPair("audit.log", "view")));
    }

    @Test
    @DisplayName("org units: list, one and create answer with the unit properties; a root has no parentId")
    void orgUnits() throws Exception {
        var repository = mock(MdOrgUnitRepository.class);
        var root = new MdOrgUnitRepository.OrgUnitRecord(1L, null, "HQ", "Company", "company", "A", 0, AT, AT);
        var child = new MdOrgUnitRepository.OrgUnitRecord(2L, 1L, "SALES", "Sales", "department", "A", 1, AT, AT);
        when(repository.listAll()).thenReturn(List.of(root, child));
        when(repository.findById(1L)).thenReturn(Optional.of(root));
        when(repository.findById(2L)).thenReturn(Optional.of(child));
        when(repository.create(1L, "SALES", "Sales", "department", 1)).thenReturn(child);
        when(scope.getUserAssignments(42L)).thenReturn(new MdOrgUnitDtos.UserAssignments(42L, List.of(2L), null));
        when(scope.getRoleScopeRule(3L)).thenReturn(new MdOrgUnitDtos.RoleRule(3L, MdScopeService.RULE_ALL));
        MockMvc mvc = mvc(new MdOrgUnitController(new MdOrgUnitService(repository, scope, audit), scope));

        JsonNode list = json(mvc, get("/api/v1/iam/org-units"), 200);
        assertThat(keys(list.get(1))).isEqualTo(ORG_UNIT);
        assertThat(keys(list.get(0))).doesNotContain("parentId").hasSize(ORG_UNIT.size() - 1);
        assertThat(keys(json(mvc, get("/api/v1/iam/org-units/2"), 200))).isEqualTo(ORG_UNIT);
        assertThat(keys(json(
                        mvc,
                        post("/api/v1/iam/org-units")
                                .content("{\"parentId\":1,\"code\":\"SALES\",\"name\":\"Sales\",\"orderNo\":1}"),
                        201)))
                .isEqualTo(ORG_UNIT);
        assertThat(keys(json(mvc, get("/api/v1/iam/org-units/users/42"), 200)))
                .isEqualTo(Set.of("userId", "orgUnitIds", "legacyOrgUnitId"));
        assertThat(keys(json(mvc, get("/api/v1/iam/org-units/roles/3/rule"), 200)))
                .isEqualTo(Set.of("roleId", "rule"));
    }

    @Test
    @DisplayName("custom fields: list and create answer with the definition, options as a JSON string")
    void customFields() throws Exception {
        var repository = mock(MdCustomFieldRepository.class);
        var record = new MdCustomFieldRepository.CustomFieldRecord(
                5L, "USER", "cf_shift", "Смена", "select", false, "day", "[\"day\",\"night\"]", 1, AT);
        when(repository.findByEntityType("USER")).thenReturn(List.of(record));
        when(repository.create(
                        eq("USER"),
                        eq("cf_shift"),
                        eq("Смена"),
                        eq("select"),
                        anyBoolean(),
                        anyString(),
                        any(),
                        anyInt()))
                .thenReturn(record);
        MockMvc mvc = mvc(new MdCustomFieldController(new MdCustomFieldService(repository, audit)));

        JsonNode list = json(mvc, get("/api/v1/custom-fields").param("entityType", "USER"), 200);
        assertThat(keys(list.get(0))).isEqualTo(CUSTOM_FIELD);
        assertThat(list.get(0).get("optionsJson").isString()).isTrue();
        assertThat(keys(json(
                        mvc,
                        post("/api/v1/custom-fields")
                                .content("{\"entityType\":\"USER\",\"code\":\"cf_shift\",\"name\":\"Смена\","
                                        + "\"fieldType\":\"select\",\"defaultValue\":\"day\","
                                        + "\"options\":[\"day\",\"night\"],\"orderNo\":1}"),
                        201)))
                .isEqualTo(CUSTOM_FIELD);
    }

    @Test
    @DisplayName("users: one, create and the page answer with the safe view; the flat list filters reach the service")
    void users() throws Exception {
        when(users.findById(42L)).thenReturn(Optional.of(user(42L)));
        when(users.create(any(), isNull())).thenReturn(user(43L));
        when(roles.getUserRoleIds(anyLong())).thenReturn(List.of(3L));
        var userService = new MdUserService(
                users,
                roles,
                mock(MdCustomFieldService.class),
                mock(PasswordHasher.class),
                mock(PasswordValidator.class),
                mock(SearchChangePublisher.class),
                audit,
                scope);
        var listService = mock(MdUserListService.class);
        var view = MdUserView.from(user(42L), List.of(3L));
        when(listService.pageViews(
                        isNull(),
                        eq(20),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq("ann"),
                        eq(new UserListFilters("A", 3L, 9L, true))))
                .thenReturn(new KeysetPage<>(List.of(view), "next", true, 1));
        MockMvc mvc = mvc(new MdUserController(userService, mock(MdUserSecurityService.class), listService));

        assertThat(keys(json(mvc, get("/api/v1/iam/users/42"), 200))).isEqualTo(USER);
        assertThat(keys(json(
                        mvc,
                        post("/api/v1/iam/users")
                                .content("{\"name\":\"Ann\",\"login\":\"ann\",\"email\":\"ann@example.invalid\","
                                        + "\"password\":\"Str0ng!Pass\",\"roleIds\":[3]}"),
                        201)))
                .isEqualTo(USER);
        JsonNode page = json(
                mvc,
                get("/api/v1/iam/users")
                        .param("limit", "20")
                        .param("search", "ann")
                        .param("state", "A")
                        .param("roleId", "3")
                        .param("managerId", "9")
                        .param("is2faEnabled", "true"),
                200);
        assertThat(keys(page)).isEqualTo(Set.of("items", "nextCursor", "hasMore", "totalEstimated"));
        assertThat(keys(page.get("items").get(0))).isEqualTo(USER);
    }

    private static MdUserRepository.UserRecord user(Long id) {
        return new MdUserRepository.UserRecord(
                id,
                "Ann",
                "ann",
                "ann@example.invalid",
                "+998900000000",
                "secret-hash",
                "A",
                9L,
                "ru",
                "UTC",
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Map.of("grade", 3),
                true,
                false,
                AT,
                AT,
                AT,
                1L,
                1L,
                4L);
    }

    private static MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JSON))
                .build();
    }

    private static JsonNode json(MockMvc mvc, MockHttpServletRequestBuilder request, int status) throws Exception {
        return JSON.readTree(send(mvc, request).andStatus(status).getContentAsString());
    }

    private static Answer send(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        return new Answer(mvc.perform(request.contentType("application/json").characterEncoding("UTF-8"))
                .andReturn()
                .getResponse());
    }

    private record Answer(MockHttpServletResponse response) {
        MockHttpServletResponse andStatus(int status) throws Exception {
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
            return response;
        }
    }

    private static Set<String> keys(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }
}
