package com.greenwhite.dwh.instance.ms.task;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** HTTP-проверка массовых действий над задачами {@code POST /api/v1/tasks/bulk} (роадмап п. 17). */
class MsTaskBulkControllerTest extends EmbeddedPostgresTest {

    private static final String BULK = "/api/v1/tasks/bulk";
    private static final String PASSWORD = "StrongPassword2026!";

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private MdUserService users;
    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    private record Session(Cookie session, Cookie csrf) {
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
    @DisplayName("смена статуса: каждая задача отдельно, чужая или несуществующая не мешает остальным, повторы схлопнуты")
    void statusChangesEachTaskAndReportsFailuresPerItem() throws Exception {
        Session admin = login(user("chief_admin"));
        long a = createTask(admin, "TEST bulk a");
        long b = createTask(admin, "TEST bulk b");
        long missing = 999_999_999L;
        List<Map<String, Object>> statuses = read(send(admin, get("/api/v1/tasks/statuses"), null), "$");
        long target = ((Number) statuses.getLast().get("id")).longValue();

        var response = send(admin, post(BULK), Map.of("action", "status", "ids", List.of(a, missing, b, a),
                "params", Map.of("statusId", target)));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat((Integer) read(response, "$.succeeded")).isEqualTo(2);
        assertThat((Integer) read(response, "$.failed")).isEqualTo(1);
        assertThat((List<Integer>) read(response, "$.results[*].id")).containsExactly((int) a, (int) missing, (int) b);
        assertThat((List<Boolean>) read(response, "$.results[*].ok")).containsExactly(true, false, true);
        assertThat((String) read(response, "$.results[1].code")).isIn("not_found", "task_not_found");
        for (long id : List.of(a, b)) {
            assertThat(jdbc.sql("select status_id from ms_tasks where id = :id").param("id", id).query(Long.class).single())
                    .isEqualTo(target);
            assertThat(jdbc.sql("select count(*) from audit_log where table_name = 'ms_tasks' and row_pk = :pk and event = 'U'")
                    .param("pk", Long.toString(id)).query(Long.class).single()).isPositive();
        }
    }

    @Test
    @DisplayName("смена приоритета и проверка запроса: действие, параметры, число записей")
    void priorityAndRequestChecks() throws Exception {
        Session admin = login(user("chief_admin"));
        long a = createTask(admin, "TEST bulk priority");

        var ok = send(admin, post(BULK), Map.of("action", "priority", "ids", List.of(a), "params", Map.of("priority", "critical")));
        assertThat((Integer) read(ok, "$.succeeded")).isEqualTo(1);
        assertThat(jdbc.sql("select priority from ms_tasks where id = :id").param("id", a).query(String.class).single())
                .isEqualTo("critical");

        assertInvalid(send(admin, post(BULK), Map.of("action", "priority", "ids", List.of(a), "params", Map.of("priority", "urgent"))),
                "params.priority");
        assertInvalid(send(admin, post(BULK), Map.of("action", "status", "ids", List.of(a), "params", Map.of("statusId", -5))),
                "params.statusId");
        assertInvalid(send(admin, post(BULK), Map.of("action", "delete-everything", "ids", List.of(a))), "action");
        assertInvalid(send(admin, post(BULK), Map.of("action", "priority", "ids", List.of(), "params", Map.of("priority", "low"))), "ids");
        List<Long> tooMany = new ArrayList<>(Collections.nCopies(101, 0L));
        for (int i = 0; i < tooMany.size(); i++) {
            tooMany.set(i, (long) i + 1);
        }
        assertInvalid(send(admin, post(BULK), Map.of("action", "priority", "ids", tooMany, "params", Map.of("priority", "low"))), "ids");
    }

    @Test
    @DisplayName("без права на изменение задач — 403, ничего не меняется")
    void readOnlyRoleIsRefused() throws Exception {
        Session admin = login(user("chief_admin"));
        long a = createTask(admin, "TEST bulk readonly");
        Session auditor = login(user("auditor"));

        var refused = send(auditor, post(BULK), Map.of("action", "priority", "ids", List.of(a), "params", Map.of("priority", "low")));

        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("select priority from ms_tasks where id = :id").param("id", a).query(String.class).single())
                .isNotEqualTo("low");
    }

    private static void assertInvalid(MockHttpServletResponse response, String field) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        assertThat((List<String>) read(response, "$.errors[*].field")).containsExactly(field);
    }

    private long createTask(Session s, String title) throws Exception {
        var created = send(s, post("/api/v1/tasks"), Map.of("title", title, "priority", "medium"));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) read(created, "$.id")).longValue();
    }

    private String user(String role) {
        String login = "bulk-" + role.replace('_', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
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
        return new Session(session, csrf);
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
