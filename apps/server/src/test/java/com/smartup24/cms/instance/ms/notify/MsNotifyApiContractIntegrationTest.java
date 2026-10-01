package com.smartup24.cms.instance.ms.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.config.idempotency.IdempotencyFilter;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.2: the JSON property sets the announcements, inbox and notification settings screens read.
 * The API answers through DTOs of {@code ms.notify.api}; these sets pin the wire format. Null properties are left
 * out of the JSON ({@code non_null}), so each set matches the fixture it reads.
 */
class MsNotifyApiContractIntegrationTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DRAFT = Set.of(
            "id",
            "titleJson",
            "bodyJson",
            "bannerType",
            "state",
            "createdBy",
            "createdAt",
            "modifiedAt",
            "lockVersion");

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MdUserService users;

    @Autowired
    private MsNotificationService notifications;

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
    @DisplayName("3.2: announcements, as managed and as read, keep their JSON properties through the lifecycle")
    void announcementResponsesKeepTheirProperties() throws Exception {
        Session s = login(user());

        Map<String, Object> created = created(send(
                s,
                post("/api/v1/announcements"),
                Map.of(
                        "titleJson", Map.of("ru", "TEST заголовок"),
                        "bodyJson", Map.of("ru", "TEST текст"),
                        "bannerType", "INFO")));
        assertKeys(created, DRAFT);
        long id = ((Number) created.get("id")).longValue();

        Map<String, Object> updated = object(ok(send(
                s,
                put("/api/v1/announcements/" + id),
                Map.of(
                        "titleJson", Map.of("ru", "TEST заголовок 2"),
                        "bodyJson", Map.of("ru", "TEST текст 2"),
                        "bannerType", "WARNING",
                        "lockVersion", created.get("lockVersion")))));
        assertKeys(updated, DRAFT);

        Map<String, Object> published = object(ok(send(
                s,
                post("/api/v1/announcements/" + id + "/publish"),
                Map.of("lockVersion", updated.get("lockVersion")))));
        assertKeys(published, with(DRAFT, "publishedAt"));
        assertKeys(
                array(ok(send(s, get("/api/v1/announcements/active?language=ru"), null))).stream()
                        .filter(item -> ((Number) item.get("id")).longValue() == id)
                        .findFirst()
                        .orElseThrow(),
                Set.of("id", "title", "body", "bannerType", "publishedAt"));

        Map<String, Object> archived = object(ok(send(
                s,
                post("/api/v1/announcements/" + id + "/archive"),
                Map.of("lockVersion", published.get("lockVersion")))));
        assertKeys(archived, with(with(DRAFT, "publishedAt"), "archivedAt"));
        assertKeys(
                items(ok(send(s, get("/api/v1/announcements/manage?limit=200"), null))).stream()
                        .filter(item -> ((Number) item.get("id")).longValue() == id)
                        .findFirst()
                        .orElseThrow(),
                with(with(DRAFT, "publishedAt"), "archivedAt"));
    }

    @Test
    @DisplayName("3.2: the inbox and the notification settings keep their JSON properties")
    void inboxAndPreferencesKeepTheirProperties() throws Exception {
        String login = user();
        long userId = jdbc.sql("select id from md_users where login = :login")
                .param("login", login)
                .query(Long.class)
                .single();
        Session s = login(login);
        notifications.sendInAppNotification(userId, "info", "TEST title", "TEST body", "/tasks/1", "task:1");

        assertKeys(
                first(items(ok(send(s, get("/api/v1/notifications/inbox"), null)))),
                Set.of("id", "userId", "type", "title", "body", "formLink", "sourceCode", "isRead", "createdAt"));

        var saved = send(
                s,
                put("/api/v1/notifications/preferences"),
                List.of(Map.of("eventType", "TASK_ASSIGNED", "channel", "EMAIL", "isEnabled", false)));
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(204);
        List<Map<String, Object>> preferences = array(ok(send(s, get("/api/v1/notifications/preferences"), null)));
        assertKeys(first(preferences), Set.of("userId", "eventType", "channel", "isEnabled"));
        assertThat(preferences.getFirst().get("isEnabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("3.5: the inbox is read newest first a page at a time; a limit over 100 or a bad cursor is 422")
    void inboxPages() throws Exception {
        String login = user();
        long userId = jdbc.sql("select id from md_users where login = :login")
                .param("login", login)
                .query(Long.class)
                .single();
        Session s = login(login);
        for (int i = 1; i <= 3; i++) {
            notifications.sendInAppNotification(userId, "info", "TEST " + i, "TEST body", "/tasks/1", "task:" + i);
        }

        Map<String, Object> first = object(ok(send(s, get("/api/v1/notifications/inbox?limit=2"), null)));
        assertThat(first.get("hasMore")).isEqualTo(true);
        assertThat(titles(first)).containsExactly("TEST 3", "TEST 2");
        Map<String, Object> second =
                object(ok(send(s, get("/api/v1/notifications/inbox?limit=2&cursor=" + first.get("nextCursor")), null)));
        assertThat(second.get("hasMore")).isEqualTo(false);
        assertThat(second.get("nextCursor")).isNull();
        assertThat(titles(second)).containsExactly("TEST 1");

        assertThat(send(s, get("/api/v1/notifications/inbox?limit=101"), null).getStatus())
                .isEqualTo(422);
        assertThat(send(s, get("/api/v1/notifications/inbox?cursor=not-a-cursor"), null)
                        .getStatus())
                .isEqualTo(422);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> titles(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items"))
                .stream().map(item -> item.get("title")).toList();
    }

    private static void assertKeys(Map<String, Object> node, Set<String> expected) {
        assertThat(node.keySet()).as("JSON properties of %s", node).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static Set<String> with(Set<String> set, String added) {
        var result = new java.util.HashSet<>(set);
        result.add(added);
        return Set.copyOf(result);
    }

    private static Map<String, Object> first(List<Map<String, Object>> items) {
        assertThat(items).isNotEmpty();
        return items.getFirst();
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

    private String user() {
        String login = "contract-notify-" + UUID.randomUUID().toString().substring(0, 8);
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

    private MockHttpServletResponse send(Session s, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        request.cookie(s.session(), s.csrf())
                .header("X-XSRF-TOKEN", s.csrf().getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType("application/json").content(JSON.writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isNotEqualTo(500);
        return response;
    }
}
