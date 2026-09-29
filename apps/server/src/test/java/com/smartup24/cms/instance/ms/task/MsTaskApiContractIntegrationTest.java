package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.config.idempotency.IdempotencyFilter;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.2: the JSON property sets the tasks, projects and comments screens read. The API answers
 * through DTOs of {@code ms.task.api}; these sets pin the wire format so a change of a DTO fails here first.
 * Null properties are left out of the JSON ({@code non_null}), so each set matches the fixture it reads.
 */
class MsTaskApiContractIntegrationTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> TASK = Set.of(
            "id",
            "projectId",
            "parentTaskId",
            "title",
            "descriptionMarkdown",
            "statusId",
            "priority",
            "reporterId",
            "attributes",
            "beginTime",
            "endTime",
            "createdAt",
            "modifiedAt",
            "createdBy",
            "modifiedBy",
            "revision");
    private static final Set<String> ROOT_TASK = without(TASK, "parentTaskId");

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MdUserService users;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    private record Session(Cookie session, Cookie csrf) {}

    @BeforeEach
    void setUp() {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity());
        IdempotencyFilter idempotency =
                wac.getBeanProvider(IdempotencyFilter.class).getIfAvailable();
        if (idempotency != null) {
            builder.addFilters(idempotency);
        }
        mvc = builder.build();
    }

    @Test
    @DisplayName("3.2: tasks, their detail, files, statuses, types and project stats keep their JSON properties")
    void taskResponsesKeepTheirProperties() throws Exception {
        String login = user();
        long userId = userId(login);
        Session s = login(login);

        long projectId =
                id(created(send(s, post("/api/v1/tasks/projects"), Map.of("name", "TEST contract " + suffix()))));
        Map<String, Object> parent = created(send(
                s,
                post("/api/v1/tasks"),
                Map.of(
                        "title", "TEST contract parent",
                        "descriptionMarkdown", "body",
                        "projectId", projectId,
                        "priority", "medium",
                        "responsibleUserId", userId,
                        "beginTime", "2026-09-01T09:00:00Z",
                        "endTime", "2099-09-30T18:00:00Z")));
        assertKeys(parent, ROOT_TASK);
        long parentId = id(parent);
        Map<String, Object> child = created(send(
                s,
                post("/api/v1/tasks"),
                Map.of(
                        "title", "TEST contract child",
                        "descriptionMarkdown", "child body",
                        "projectId", projectId,
                        "parentTaskId", parentId,
                        "priority", "high",
                        "beginTime", "2026-09-01T09:00:00Z",
                        "endTime", "2099-09-30T18:00:00Z")));
        assertKeys(child, TASK);
        UUID fileId = upload(s);
        assertThat(send(s, post("/api/v1/tasks/" + parentId + "/files"), Map.of("fileId", fileId))
                        .getStatus())
                .isEqualTo(204);

        Map<String, Object> detail = object(ok(send(s, get("/api/v1/tasks/" + parentId), null)));
        assertKeys(detail, Set.of("task", "members", "subtasks", "ancestors", "files"));
        assertKeys(map(detail.get("task")), ROOT_TASK);
        assertKeys(
                first(detail.get("members")),
                Set.of("taskId", "userId", "userName", "userLogin", "userEmail", "involveKind", "isViewed"));
        assertKeys(first(detail.get("subtasks")), TASK);
        assertThat(list(detail.get("ancestors"))).isEmpty();
        Set<String> file = Set.of("fileId", "fileName", "sizeBytes", "mimeType", "createdAt");
        assertKeys(first(detail.get("files")), file);
        assertKeys(
                map(object(ok(send(s, get("/api/v1/tasks/" + id(child)), null))).get("ancestors"), 0), ROOT_TASK);

        assertKeys(first(array(ok(send(s, get("/api/v1/tasks/" + parentId + "/files"), null)))), file);
        assertKeys(first(array(ok(send(s, get("/api/v1/tasks/" + parentId + "/subtasks"), null)))), TASK);

        Map<String, Object> page =
                object(ok(send(s, get("/api/v1/tasks?projectId=" + projectId + "&limit=1&sort=title"), null)));
        assertKeys(page, Set.of("items", "nextCursor", "hasMore", "totalEstimated", "totalExact"));
        assertKeys(first(page.get("items")), TASK);

        Set<String> status = Set.of("id", "pcode", "name", "color", "orderNo", "isTerminal", "revision");
        assertKeys(
                created(send(
                        s,
                        post("/api/v1/tasks/statuses"),
                        Map.of("pcode", "c" + suffix(), "name", "TEST status", "color", "#123456", "orderNo", 90))),
                status);
        assertKeys(first(array(ok(send(s, get("/api/v1/tasks/statuses"), null)))), status);

        Set<String> type =
                Set.of("id", "code", "name", "icon", "color", "orderNo", "isSystem", "createdAt", "revision");
        assertKeys(
                created(send(
                        s,
                        post("/api/v1/tasks/types"),
                        Map.of(
                                "code",
                                "t" + suffix(),
                                "name",
                                "TEST type",
                                "icon",
                                "bug",
                                "color",
                                "#654321",
                                "orderNo",
                                90))),
                type);
        assertKeys(
                array(ok(send(s, get("/api/v1/tasks/types"), null))).stream()
                        .filter(item -> item.get("icon") != null && item.get("color") != null)
                        .findFirst()
                        .orElseThrow(),
                type);

        assertKeys(
                array(ok(send(s, get("/api/v1/tasks/projects/stats"), null))).stream()
                        .filter(item -> ((Number) item.get("projectId")).longValue() == projectId)
                        .findFirst()
                        .orElseThrow(),
                Set.of("projectId", "totalTasks", "activeTasks", "doneTasks"));
    }

    @Test
    @DisplayName("3.2: projects, their members and task comments keep their JSON properties")
    void projectAndCommentResponsesKeepTheirProperties() throws Exception {
        String login = user();
        long userId = userId(login);
        Session s = login(login);
        Set<String> project =
                Set.of("id", "name", "description", "state", "attributes", "createdAt", "createdBy", "revision");

        Map<String, Object> created = created(send(
                s,
                post("/api/v1/tasks/projects"),
                Map.of("name", "TEST contract project " + suffix(), "description", "about", "state", "A")));
        assertKeys(created, project);
        long projectId = id(created);
        assertKeys(object(ok(send(s, get("/api/v1/tasks/projects/" + projectId), null))), project);
        assertKeys(
                array(ok(send(s, get("/api/v1/tasks/projects"), null))).stream()
                        .filter(item -> ((Number) item.get("id")).longValue() == projectId)
                        .findFirst()
                        .orElseThrow(),
                project);
        assertThat(send(
                                s,
                                post("/api/v1/tasks/projects/" + projectId + "/members"),
                                Map.of("userId", userId, "accessKind", "W"))
                        .getStatus())
                .isEqualTo(204);
        assertKeys(
                first(array(ok(send(s, get("/api/v1/tasks/projects/" + projectId + "/members"), null)))),
                Set.of("projectId", "userId", "userName", "userEmail", "accessKind"));

        long taskId = id(created(send(s, post("/api/v1/tasks"), Map.of("title", "TEST contract comments"))));
        UUID fileId = upload(s);
        Set<String> comment =
                Set.of("id", "taskId", "userId", "textMarkdown", "fileIds", "createdAt", "userName", "userLogin");
        assertKeys(
                created(send(
                        s,
                        post("/api/v1/tasks/" + taskId + "/comments"),
                        Map.of("textMarkdown", "TEST comment", "fileIds", List.of(fileId)))),
                comment);
        assertKeys(first(items(ok(send(s, get("/api/v1/tasks/" + taskId + "/comments"), null)))), comment);
    }

    private static void assertKeys(Map<String, Object> node, Set<String> expected) {
        assertThat(node.keySet()).as("JSON properties of %s", node).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static Set<String> without(Set<String> set, String removed) {
        return set.stream().filter(key -> !key.equals(removed)).collect(java.util.stream.Collectors.toSet());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static long id(Map<String, Object> node) {
        return ((Number) node.get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        return (Map<String, Object>) node;
    }

    private static Map<String, Object> map(Object node, int index) {
        return map(list(node).get(index));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object node) {
        return (List<Object>) node;
    }

    private static Map<String, Object> first(Object node) {
        List<Object> items = list(node);
        assertThat(items).isNotEmpty();
        return map(items.getFirst());
    }

    private static MockHttpServletResponse ok(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response;
    }

    private static Map<String, Object> created(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return object(response);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    /** The items of a page (plan item 3.5: growing collections answer KeysetPage). */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(MockHttpServletResponse response) throws Exception {
        Map<String, Object> page = object(response);
        assertThat(page).containsKeys("items", "hasMore", "totalEstimated", "totalExact");
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> array(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), List.class);
    }

    private UUID upload(Session s) throws Exception {
        var request = multipart("/api/v1/files/upload")
                .file(new MockMultipartFile(
                        "file", "contract.txt", "text/plain", "contract".getBytes(StandardCharsets.UTF_8)));
        var response = send(s, request, null);
        assertThat(response.getStatus()).as(response.getContentAsString()).isIn(200, 201);
        return UUID.fromString((String) object(response).get("id"));
    }

    private String user() {
        String login = "contract-task-" + suffix();
        Long systemId = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        Long roleId = jdbc.sql("select id from md_roles where pcode = 'chief_admin'")
                .query(Long.class)
                .single();
        users.createUser(
                "TEST " + login,
                login,
                login + "@test.local",
                null,
                PASSWORD,
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                List.of(roleId),
                systemId);
        return login;
    }

    private long userId(String login) {
        return jdbc.sql("select id from md_users where login = :login")
                .param("login", login)
                .query(Long.class)
                .single();
    }

    private Session login(String login) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(JSON.writeValueAsString(
                                Map.of("login", login, "password", PASSWORD, "deviceInfo", "test"))))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        Cookie session = response.getCookie(KauthPref.SESSION_COOKIE_NAME);
        Cookie csrf = response.getCookie("XSRF-TOKEN");
        if (csrf == null) {
            csrf = mvc.perform(get("/api/v1/auth/me").cookie(session))
                    .andReturn()
                    .getResponse()
                    .getCookie("XSRF-TOKEN");
        }
        assertThat(csrf).as("XSRF-TOKEN cookie").isNotNull();
        return new Session(session, csrf);
    }

    private MockHttpServletResponse send(Session s, AbstractMockHttpServletRequestBuilder<?> request, Object body)
            throws Exception {
        request.cookie(s.session(), s.csrf()).header("X-XSRF-TOKEN", s.csrf().getValue());
        if (!(request instanceof MockMultipartHttpServletRequestBuilder)) {
            // The idempotency filter refuses multipart uploads that carry a key.
            request.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        if (body != null) {
            request.contentType("application/json").content(JSON.writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isNotEqualTo(500);
        return response;
    }
}
