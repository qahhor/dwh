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
import java.util.ArrayList;
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
 * Plan 10/10, item 3.2: the JSON property sets the task screens read from the module's own endpoints — participants,
 * files, project members and comments — through DTOs of {@code ms.task.api}; these sets pin the wire format so a change
 * of a DTO fails here first. The tasks and projects themselves are records of the general runtime (ADR-0032, 8), whose
 * shape the entity contract kit pins. Null properties are left out of the JSON ({@code non_null}).
 */
class MsTaskApiContractIntegrationTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TASKS = "/api/v1/entities/ms.tasks";

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
    @DisplayName("3.2: the participants and files of a task keep their JSON properties")
    void taskResponsesKeepTheirProperties() throws Exception {
        String login = user();
        long userId = userId(login);
        Session s = login(login);

        long projectId = project(s, "TEST contract " + suffix());
        long taskId = task(
                s,
                Map.of(
                        "title", "TEST contract parent",
                        "descriptionMarkdown", "body",
                        "projectId", projectId,
                        "responsibleId", userId,
                        "beginTime", "2026-09-01T09:00:00Z",
                        "endTime", "2099-09-30T18:00:00Z"));
        UUID fileId = upload(s);
        assertThat(send(s, post("/api/v1/tasks/" + taskId + "/files"), Map.of("fileId", fileId))
                        .getStatus())
                .isEqualTo(204);

        assertKeys(
                first(array(ok(send(s, get("/api/v1/tasks/" + taskId + "/members"), null)))),
                Set.of("taskId", "userId", "userName", "userLogin", "userEmail", "involveKind", "isViewed"));
        assertKeys(
                first(array(ok(send(s, get("/api/v1/tasks/" + taskId + "/files"), null)))),
                Set.of("fileId", "fileName", "sizeBytes", "mimeType", "createdAt"));
    }

    @Test
    @DisplayName("3.2: projects, their members and task comments keep their JSON properties")
    void projectAndCommentResponsesKeepTheirProperties() throws Exception {
        String login = user();
        long userId = userId(login);
        Session s = login(login);
        long projectId = project(s, "TEST contract project " + suffix());
        addMember(s, projectId, userId, "W");
        assertKeys(
                first(items(ok(send(s, get("/api/v1/tasks/projects/" + projectId + "/members/page"), null)))),
                Set.of("projectId", "userId", "userName", "userEmail", "accessKind"));

        long taskId = task(s, Map.of("title", "TEST contract comments"));
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

    @Test
    @DisplayName("3.5: the members of a project come a page at a time by name; a bad limit or cursor is 422")
    void projectMembersArePaged() throws Exception {
        Session s = login(user());
        long projectId = project(s, "TEST members page " + suffix());
        List<Long> members = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long member = userId(user());
            members.add(member);
            addMember(s, projectId, member, "R");
        }
        String path = "/api/v1/tasks/projects/" + projectId + "/members/page";

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        do {
            var request = get(path).param("limit", "2");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            Map<String, Object> page = object(ok(send(s, request, null)));
            items(page).forEach(item -> seen.add(((Number) item.get("userId")).longValue()));
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(members).doesNotHaveDuplicates();

        assertThat(send(s, get(path).param("limit", "0"), null).getStatus()).isEqualTo(422);
        assertThat(send(s, get(path).param("limit", "201"), null).getStatus()).isEqualTo(422);
        assertThat(send(s, get(path).param("cursor", "not-a-cursor"), null).getStatus())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("3.5: task rows name their project; a picker finds it in the paged project list by name")
    void taskRowsNameTheirProjectAndPickersSearchProjects() throws Exception {
        Session s = login(user());
        String name = "TEST picker " + suffix();
        long projectId = project(s, name);
        task(s, Map.of("title", "TEST named", "projectId", projectId));
        String filter = "[{\"field\":\"projectId\",\"op\":\"eq\",\"value\":" + projectId + "}]";
        Map<String, Object> page = object(ok(send(s, get(TASKS).param("filter", filter), null)));
        assertThat(items(page)).extracting(row -> row.get("projectName")).containsExactly(name);

        Map<String, Object> found = object(
                ok(send(s, get("/api/v1/entities/ms.projects").param("q", name).param("limit", "20"), null)));
        assertThat(items(found)).extracting(row -> row.get("name")).containsExactly(name);
        assertThat(send(s, get("/api/v1/entities/ms.projects").param("limit", "201"), null)
                        .getStatus())
                .isEqualTo(422);
    }

    /** A task created on the general runtime (ADR-0032, 8). */
    private long task(Session s, Map<String, Object> values) throws Exception {
        return id(created(send(s, post(TASKS), values)));
    }

    /** A project created on the general runtime (ADR-0032, 8). */
    private long project(Session s, String name) throws Exception {
        return id(created(send(s, post("/api/v1/entities/ms.projects"), Map.of("name", name))));
    }

    /** A member added by the project's record action, from its current revision. */
    private void addMember(Session s, long projectId, long userId, String accessKind) throws Exception {
        long revision = ((Number) object(ok(send(s, get("/api/v1/entities/ms.projects/" + projectId), null)))
                        .get("revision"))
                .longValue();
        assertThat(send(
                                s,
                                post("/api/v1/entities/ms.projects/" + projectId + "/actions/add_member")
                                        .header("If-Match", "\"" + revision + "\""),
                                Map.of("userId", userId, "accessKind", accessKind))
                        .getStatus())
                .isEqualTo(200);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static void assertKeys(Map<String, Object> node, Set<String> expected) {
        assertThat(node.keySet()).as("JSON properties of %s", node).containsExactlyInAnyOrderElementsOf(expected);
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
