package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.config.idempotency.IdempotencyFilter;
import com.greenwhite.dwh.instance.kauth.pref.KauthPref;
import com.greenwhite.dwh.instance.md.service.MdListViewService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** HTTP-проверка сохранённых представлений списка {@code /api/v1/list-views/{list}} (ADR-0016, роадмап п. 14). */
class MdListViewControllerTest extends EmbeddedPostgresTest {

    private static final String BASE = "/api/v1/list-views/upl.sources";
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

    private static Map<String, Object> view(String name, Object state, boolean isDefault, Integer lockVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("state", state);
        body.put("isDefault", isDefault);
        body.put("lockVersion", lockVersion);
        return body;
    }

    private static Map<String, Object> state(Object order, Object hidden, Object widths, Object sort, Object filter) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("order", order);
        columns.put("hidden", hidden);
        columns.put("widths", widths);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("columns", columns);
        state.put("sort", sort);
        state.put("filter", filter);
        return state;
    }

    @Test
    @DisplayName("своё представление: создаётся в канонической форме, одно по умолчанию, правится с lockVersion, удаляется")
    void ownViewLifecycle() throws Exception {
        Session analyst = login(user("analyst"));
        var filter = List.of(Map.of("field", "periodicity", "op", "in", "value", List.of("month")));

        var created = send(analyst, post(BASE), view("  Месячные  ",
                state(List.of("name", "code"), List.of("hasDraft"), Map.of("name", "240px"), "-name", filter), true, null));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        long first = ((Number) read(created, "$.id")).longValue();
        assertThat((String) read(created, "$.name")).isEqualTo("Месячные");
        assertThat((Boolean) read(created, "$.isDefault")).isTrue();
        assertThat((List<String>) read(created, "$.state.columns.order")).containsExactly("name", "code");
        assertThat((String) read(created, "$.state.columns.widths.name")).isEqualTo("240px");
        assertThat((String) read(created, "$.state.sort")).isEqualTo("-name");
        assertThat((String) read(created, "$.state.filter[0].op")).isEqualTo("in");

        var second = send(analyst, post(BASE), view("Все", Map.of(), true, null));
        assertThat(second.getStatus()).as(second.getContentAsString()).isEqualTo(201);
        assertThat((List<String>) read(second, "$.state.columns.order")).isEmpty();
        assertThat(read(second, "$.state.sort") == null).isTrue();

        var listed = send(analyst, get(BASE), null);
        assertThat((List<String>) read(listed, "$[*].name")).containsExactly("Все", "Месячные");
        assertThat((List<Boolean>) read(listed, "$[*].isDefault")).containsExactly(true, false);

        var duplicate = send(analyst, post(BASE), view("все", Map.of(), false, null));
        assertThat(duplicate.getStatus()).isEqualTo(422);
        assertThat((String) read(duplicate, "$.detail")).isEqualTo(MdListViewService.LIST_VIEW_NAME_TAKEN);

        var renamed = send(analyst, put(BASE + "/" + first), view("Месячные по имени", Map.of("sort", "name"), false, 0));
        assertThat(renamed.getStatus()).as(renamed.getContentAsString()).isEqualTo(200);
        assertThat((Integer) read(renamed, "$.lockVersion")).isEqualTo(1);
        var stale = send(analyst, put(BASE + "/" + first), view("Снова", Map.of(), false, 0));
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat((String) read(stale, "$.detail")).isEqualTo("STALE_VERSION");

        assertThat(send(analyst, delete(BASE + "/" + first), null).getStatus()).isEqualTo(204);
        assertThat(send(analyst, delete(BASE + "/" + first), null).getStatus()).isEqualTo(404);
        assertThat((List<String>) read(send(analyst, get(BASE), null), "$[*].name")).containsExactly("Все");
        assertThat(jdbc.sql("select event from audit_log where table_name = 'md_list_views' and row_pk = :pk order by id")
                .param("pk", Long.toString(first)).query(String.class).list()).containsExactly("I", "U", "D");
    }

    @Test
    @DisplayName("состояние проверяется по реестру полей: ошибки адресованы внутрь state")
    void stateIsCheckedAgainstTheRegistry() throws Exception {
        Session analyst = login(user("analyst"));
        Map<String, Object> bad = state(List.of("code", "secret", "code"), List.of(1), Map.of("name", "50%"), "hasDraft",
                List.of(Map.of("field", "code", "op", "gt", "value", "x")));
        bad.put("extra", true);

        var response = send(analyst, post(BASE), view("", bad, false, null));
        assertThat(response.getStatus()).isEqualTo(422);
        var single = send(analyst, post(BASE), view("Плохое", bad, false, null));
        assertThat((String) read(single, "$.detail")).isEqualTo(MdListViewService.LIST_VIEW_INVALID);
        assertThat((List<String>) read(single, "$.errors[*].field")).containsExactlyInAnyOrder(
                "state.extra", "state.columns.order[1]", "state.columns.order[2]", "state.columns.hidden[0]",
                "state.columns.widths.name", "state.filter[0].op", "state.sort");
        assertThat(jdbc.sql("select count(*) from md_list_views where name = 'Плохое'").query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("чужое представление не видно и не меняется; список без права — 404")
    void viewsBelongToTheirOwner() throws Exception {
        Session owner = login(user("analyst"));
        Session other = login(user("chief_admin"));
        var created = send(owner, post(BASE), view("Моё", Map.of(), false, null));
        long id = ((Number) read(created, "$.id")).longValue();

        assertThat((List<Object>) read(send(other, get(BASE), null), "$")).isEmpty();
        assertThat(send(other, put(BASE + "/" + id), view("Чужое", Map.of(), false, 0)).getStatus()).isEqualTo(404);
        assertThat(send(other, delete(BASE + "/" + id), null).getStatus()).isEqualTo(404);

        Session outsider = login(user("user"));
        var hidden = send(outsider, get(BASE), null);
        assertThat(hidden.getStatus()).isEqualTo(404);
        assertThat((String) read(hidden, "$.detail")).isEqualTo("QUERY_LIST_NOT_FOUND");
        assertThat(send(outsider, get("/api/v1/list-views/no.such.list"), null).getStatus()).isEqualTo(404);
    }

    private String user(String role) {
        String login = "views-" + role.replace('_', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
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
