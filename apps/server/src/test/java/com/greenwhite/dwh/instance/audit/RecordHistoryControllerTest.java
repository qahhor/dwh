package com.greenwhite.dwh.instance.audit;

import com.greenwhite.dwh.instance.config.idempotency.IdempotencyFilter;
import com.greenwhite.dwh.instance.kauth.pref.KauthPref;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import com.greenwhite.dwh.instance.support.EmbeddedPostgresTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** HTTP-проверка вкладки «История» карточки записи {@code GET /api/v1/history/{kind}/{id}} (ADR-0017, роадмап п. 18). */
class RecordHistoryControllerTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private MdUserService users;
    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    private record Session(String login, Cookie session, Cookie csrf) {
    }

    @BeforeEach
    void setUp() {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity());
        IdempotencyFilter idempotency = wac.getBeanProvider(IdempotencyFilter.class).getIfAvailable();
        if (idempotency != null) {
            builder.addFilters(idempotency);
        }
        mvc = builder.build();
    }

    @Test
    @DisplayName("история задачи: новые изменения сверху, только изменённые поля, подписи полей и автор")
    void taskHistoryListsChangesNewestFirst() throws Exception {
        Session admin = login(user("chief_admin"));
        long task = createTask(admin, "TEST history before");
        var patched = send(admin, patch("/api/v1/tasks/" + task), Map.of("title", "TEST history after", "priority", "medium"));
        assertThat(patched.getStatus()).as(patched.getContentAsString()).isLessThan(300);

        var response = send(admin, get("/api/v1/history/tasks/" + task), null);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat((List<String>) read(response, "$.items[*].event")).containsExactly("U", "I");
        // The update names only the title: the priority did not change.
        assertThat((List<String>) read(response, "$.items[0].changes[*].field")).containsExactly("title");
        assertThat((String) read(response, "$.items[0].changes[0].labelKey")).isEqualTo("task.title");
        assertThat((String) read(response, "$.items[0].changes[0].oldValue")).isEqualTo("TEST history before");
        assertThat((String) read(response, "$.items[0].changes[0].newValue")).isEqualTo("TEST history after");
        assertThat((String) read(response, "$.items[0].changedByLogin")).isEqualTo(admin.login());
        // The creation lists what was set, without the technical id.
        assertThat((List<String>) read(response, "$.items[1].changes[*].field")).contains("title").doesNotContain("id");
    }

    @Test
    @DisplayName("без права на записи — 403; неизвестный вид, несуществующая или кривая запись — 404")
    void historyNeedsTheRecordsRightAndAVisibleRecord() throws Exception {
        Session admin = login(user("chief_admin"));
        long task = createTask(admin, "TEST history rights");
        Session analyst = login(user("analyst"));

        assertThat(send(analyst, get("/api/v1/history/tasks/" + task), null).getStatus()).isEqualTo(403);
        assertThat(send(admin, get("/api/v1/history/invoices/" + task), null).getStatus()).isEqualTo(404);
        assertThat(send(admin, get("/api/v1/history/tasks/999999999"), null).getStatus()).isEqualTo(404);
        assertThat(send(admin, get("/api/v1/history/tasks/abc"), null).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("список видов с историей зависит от прав смотрящего")
    void kindsFollowTheViewersRights() throws Exception {
        Session admin = login(user("chief_admin"));
        Session analyst = login(user("analyst"));

        assertThat((List<String>) read(send(admin, get("/api/v1/history"), null), "$")).contains("tasks", "projects", "users");
        assertThat((List<String>) read(send(analyst, get("/api/v1/history"), null), "$")).isEmpty();
    }

    @Test
    @DisplayName("история пользователя: создание с подписанными полями, без учётных секретов")
    void userHistoryShowsCreationWithoutSecrets() throws Exception {
        Session admin = login(user("chief_admin"));
        String login = user("analyst");
        Long id = jdbc.sql("select id from md_users where login = :login").param("login", login).query(Long.class).single();

        var response = send(admin, get("/api/v1/history/users/" + id), null);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat((List<String>) read(response, "$.items[-1:].event")).containsExactly("I");
        List<String> fields = read(response, "$.items[-1:].changes[*].field");
        assertThat(fields).contains("name", "login").noneMatch(field -> field.toLowerCase().contains("password"));
        assertThat((List<String>) read(response, "$.items[-1:].changes[?(@.field == 'name')].labelKey")).containsExactly("iam.fio");
    }

    private long createTask(Session s, String title) throws Exception {
        var created = send(s, post("/api/v1/tasks"), Map.of("title", title, "priority", "medium"));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) read(created, "$.id")).longValue();
    }

    private String user(String role) {
        String login = "history-" + role.replace('_', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
        Long systemId = jdbc.sql("select id from md_users where login = 'system'").query(Long.class).single();
        Long roleId = jdbc.sql("select id from md_roles where pcode = :role").param("role", role)
                .query(Long.class).single();
        users.createUser("TEST " + login, login, login + "@test.local", null, PASSWORD, null, "ru", "UTC", null,
                Map.of(), false, false, List.of(roleId), systemId);
        return login;
    }

    private Session login(String login) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(json(Map.of("login", login, "password", PASSWORD, "deviceInfo", "test"))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        Cookie session = response.getCookie(KauthPref.SESSION_COOKIE_NAME);
        Cookie csrf = response.getCookie("XSRF-TOKEN");
        if (csrf == null) {
            csrf = mvc.perform(get("/api/v1/auth/me").cookie(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        }
        assertThat(csrf).as("XSRF-TOKEN cookie").isNotNull();
        return new Session(login, session, csrf);
    }

    private MockHttpServletResponse send(Session s, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.cookie(s.session(), s.csrf()).header("X-XSRF-TOKEN", s.csrf().getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType("application/json").content(json(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isNotEqualTo(500);
        return response;
    }

    private static <T> T read(MockHttpServletResponse response, String path) throws Exception {
        return JsonPath.read(response.getContentAsString(), path);
    }

    private static String json(Object value) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
    }
}
