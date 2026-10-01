package com.smartup24.cms.instance.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.md.api.NavigationItemView;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.NavigationItemService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.6, acceptance: of two concurrent saves of a record made from the same revision exactly one is
 * refused with 409; a save that names no revision is 428; and a hundred concurrent switches leave one state and an
 * audit trail that follows it.
 */
class RecordRevisionIntegrationTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MdUserService users;

    @Autowired
    private NavigationItemService navigation;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    private record Session(Cookie session, Cookie csrf) {}

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("3.6: a save without a revision is 428, a malformed one 422, and the answer carries the new ETag")
    void saveNamesItsRevision() throws Exception {
        Session s = login(user());
        long id = note(s);

        MockHttpServletResponse missing = send(s, put("/api/v1/notes/" + id), Map.of("title", "No revision"));
        assertThat(missing.getStatus()).isEqualTo(428);
        assertThat(object(missing).get("code")).isEqualTo("precondition_required");
        assertThat(send(s, put("/api/v1/notes/" + id).header("If-Match", "latest"), Map.of("title", "Bad"))
                        .getStatus())
                .isEqualTo(422);

        MockHttpServletResponse saved =
                send(s, put("/api/v1/notes/" + id).header("If-Match", "\"1\""), Map.of("title", "First"));
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
        assertThat(saved.getHeader("ETag")).isEqualTo("\"2\"");
        assertThat(object(saved).get("revision")).isEqualTo(2);

        MockHttpServletResponse stale =
                send(s, put("/api/v1/notes/" + id).header("If-Match", "\"1\""), Map.of("title", "Over it"));
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(object(stale).get("code")).isEqualTo("revision_conflict");
        assertThat(object(stale).get("messageKey")).isEqualTo("error.common.revision_conflict");
    }

    @Test
    @DisplayName("3.6: of two concurrent saves from the same revision exactly one wins and the other gets 409")
    void concurrentSavesConflict() throws Exception {
        Session s = login(user());
        long id = note(s);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> saves = new ArrayList<>();
            for (String title : List.of("Alice", "Bob")) {
                saves.add(pool.submit(() -> {
                    start.await();
                    return send(s, put("/api/v1/notes/" + id).header("If-Match", "\"1\""), Map.of("title", title))
                            .getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> save : saves) {
                statuses.add(save.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("select revision from ms_notes where id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("3.6: a hundred concurrent switches leave one state and one audit row per real change")
    void concurrentSwitchesStayConsistent() throws Exception {
        NavigationItemView item = navigation.createItem(
                new NavigationItemService.CreateNavigationItemCommand(
                        "rev-switch-" + UUID.randomUUID().toString().substring(0, 8),
                        "Switch",
                        null,
                        "custom",
                        null,
                        null,
                        "INTERNAL_ROUTE",
                        "/tasks",
                        false,
                        null,
                        10),
                null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<NavigationItemView>> switches = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                boolean active = ThreadLocalRandom.current().nextBoolean();
                switches.add(() -> {
                    start.await();
                    return navigation.setActive(item.id(), null, active);
                });
            }
            List<Future<NavigationItemView>> done = new ArrayList<>();
            for (Callable<NavigationItemView> one : switches) {
                done.add(pool.submit(one));
            }
            start.countDown();
            for (Future<NavigationItemView> one : done) {
                one.get();
            }
        } finally {
            pool.shutdownNow();
        }

        String finalState = jdbc.sql("select state from md_navigation_items where id = :id")
                .param("id", item.id())
                .query(String.class)
                .single();
        List<String[]> trail = jdbc.sql("""
                        select old_row ->> 'state' as before, new_row ->> 'state' as after
                        from audit_log
                        where table_name = 'md_navigation_items' and row_pk = :id and event = 'U'
                        order by id
                        """)
                .param("id", String.valueOf(item.id()))
                .query((rs, row) -> new String[] {rs.getString("before"), rs.getString("after")})
                .list();
        // Every audit row is a real change: it starts where the previous one ended, and the last one is the state.
        String state = "A";
        for (String[] change : trail) {
            assertThat(change[0])
                    .as("audit row starts from the state before it")
                    .isEqualTo(state);
            assertThat(change[1]).as("audit row changes the state").isNotEqualTo(change[0]);
            state = change[1];
        }
        assertThat(state).isEqualTo(finalState);
    }

    @Test
    @DisplayName("3.6: a reset of 2FA raises the user's revision, so a form opened before it gets 409")
    void securityActionRaisesTheRevision() throws Exception {
        Session admin = login(user());
        String target = "/api/v1/iam/users/" + userId(user());
        long read = revisionOf(admin, target);

        MockHttpServletResponse reset = send(admin, post(target + "/reset-2fa"), null);
        assertThat(reset.getStatus()).as(reset.getContentAsString()).isEqualTo(204);

        MockHttpServletResponse stale =
                send(admin, patch(target).header("If-Match", Revisions.etag(read)), Map.of("name", "Stale form"));
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(object(stale).get("messageKey")).isEqualTo("error.common.revision_conflict");

        long now = revisionOf(admin, target);
        assertThat(now).isGreaterThan(read);
        MockHttpServletResponse fresh =
                send(admin, patch(target).header("If-Match", Revisions.etag(now)), Map.of("name", "Fresh form"));
        assertThat(fresh.getStatus()).as(fresh.getContentAsString()).isEqualTo(204);
        assertThat(fresh.getHeader("ETag")).isEqualTo(Revisions.etag(now + 1));

        // The personal rights are part of the user: saved from its revision, 428 without one, 409 from a stale one.
        Map<String, Object> grants = Map.of("grants", List.of());
        assertThat(send(admin, put(target + "/permissions"), grants).getStatus())
                .isEqualTo(428);
        assertThat(send(admin, put(target + "/permissions").header("If-Match", Revisions.etag(now)), grants)
                        .getStatus())
                .isEqualTo(409);
        MockHttpServletResponse rights =
                send(admin, put(target + "/permissions").header("If-Match", Revisions.etag(now + 1)), grants);
        assertThat(rights.getStatus()).as(rights.getContentAsString()).isEqualTo(200);
        assertThat(rights.getHeader("ETag")).isEqualTo(Revisions.etag(now + 2));
    }

    @Test
    @DisplayName("3.6: a reorder raises the revision of every status it moves, so a stale edit gets 409")
    void reorderRaisesTheRevision() throws Exception {
        Session admin = login(user());
        List<Map<String, Object>> before = list(send(admin, get("/api/v1/tasks/statuses"), null));
        List<Long> ids =
                before.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        Map<String, Object> first = before.getFirst();
        long read = ((Number) first.get("revision")).longValue();
        try {
            MockHttpServletResponse reordered = send(admin, post("/api/v1/tasks/statuses/reorder"), ids.reversed());
            assertThat(reordered.getStatus()).as(reordered.getContentAsString()).isLessThan(300);

            MockHttpServletResponse stale = send(
                    admin,
                    patch("/api/v1/tasks/statuses/" + first.get("id")).header("If-Match", Revisions.etag(read)),
                    Map.of("color", "#123456"));
            assertThat(stale.getStatus()).isEqualTo(409);
        } finally {
            send(admin, post("/api/v1/tasks/statuses/reorder"), ids);
        }
    }

    private long revisionOf(Session s, String path) throws Exception {
        MockHttpServletResponse read = send(s, get(path), null);
        assertThat(read.getStatus()).as(read.getContentAsString()).isEqualTo(200);
        return ((Number) object(read).get("revision")).longValue();
    }

    private long userId(String login) {
        return jdbc.sql("select id from md_users where login = :login")
                .param("login", login)
                .query(Long.class)
                .single();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), List.class);
    }

    private long note(Session s) throws Exception {
        MockHttpServletResponse created = send(s, post("/api/v1/notes"), Map.of("title", "Revision probe"));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        assertThat(created.getHeader("ETag")).isEqualTo("\"1\"");
        return ((Number) object(created).get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private String user() {
        String login = "revision-" + UUID.randomUUID().toString().substring(0, 8);
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
        request.cookie(s.session(), s.csrf()).header("X-XSRF-TOKEN", s.csrf().getValue());
        if (body != null) {
            request.contentType("application/json").content(JSON.writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isNotEqualTo(500);
        return response;
    }
}
