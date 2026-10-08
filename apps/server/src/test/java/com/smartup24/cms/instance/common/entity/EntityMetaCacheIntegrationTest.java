package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.config.cache.CacheConfig;
import com.smartup24.cms.instance.config.cache.CacheInvalidations;
import com.smartup24.cms.instance.config.cache.ClusterCacheManager;
import com.smartup24.cms.instance.kauth.api.KauthPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 5.0, acceptance: {@code query-meta} and {@code form-meta} read the custom fields through the
 * cluster cache of definitions (ADR-0025), and a change of a custom field clears it on this node and on the others —
 * so the list columns and the form show a new field at once, without a restart or a stale node.
 */
class EntityMetaCacheIntegrationTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WITHIN = Duration.ofSeconds(2);

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MdUserService users;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CacheInvalidations invalidations;

    @Autowired
    private DataSource dataSource;

    private MockMvc mvc;
    private CacheInvalidations secondNode;
    private CacheManager secondCaches;

    private record Session(Cookie session, Cookie csrf) {}

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        CaffeineCacheManager local = new CaffeineCacheManager();
        local.setCacheNames(cacheManager.getCacheNames());
        secondNode = new CacheInvalidations(dataSource, jdbc);
        secondCaches = new ClusterCacheManager(local, secondNode);
        secondNode.start();
        awaitTrue(() -> secondNode.isListening() && invalidations.isListening(), Duration.ofSeconds(10));
    }

    @AfterEach
    void stopSecondNode() {
        secondNode.stop();
    }

    @Test
    @DisplayName("5.0: a new custom field is in query-meta and form-meta at once; the cache is cleared on every node")
    void newCustomFieldShowsAtOnceOnEveryNode() throws Exception {
        Session admin = login(user());
        Cache local = cacheManager.getCache(CacheConfig.CUSTOM_FIELDS_CACHE);
        Cache remote = secondCaches.getCache(CacheConfig.CUSTOM_FIELDS_CACHE);
        String code = "cache_" + UUID.randomUUID().toString().substring(0, 8);
        String key = "cfCache" + Character.toUpperCase(code.charAt(6)) + code.substring(7);

        assertThat(fieldKeys(send(admin, get("/api/v1/query-meta/ms.notes"), null)))
                .doesNotContain(key);
        assertThat(fieldKeys(send(admin, get("/api/v1/form-meta/ms.notes"), null)))
                .doesNotContain(key);
        assertThat(local.get("note"))
                .as("query-meta and form-meta read the cached definitions")
                .isNotNull();
        remote.put("note", List.of());

        MockHttpServletResponse created = send(
                admin,
                post("/api/v1/custom-fields"),
                Map.of("entityType", "NOTE", "code", code, "name", "Call time", "fieldType", "time", "orderNo", 90));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        long id = ((Number) object(created).get("id")).longValue();
        try {
            assertThat(local.get("note")).isNull();
            awaitTrue(() -> remote.get("note") == null, WITHIN);

            Map<String, Object> listed = field(send(admin, get("/api/v1/query-meta/ms.notes"), null), key);
            assertThat(listed).containsEntry("type", "time").containsEntry("label", "Call time");
            Map<String, Object> drawn = field(send(admin, get("/api/v1/form-meta/ms.notes"), null), key);
            assertThat(drawn).containsEntry("type", "time").containsEntry("attribute", code);
        } finally {
            remote.put("note", List.of());
            assertThat(send(admin, delete("/api/v1/custom-fields/" + id), null).getStatus())
                    .isEqualTo(204);
        }
        awaitTrue(() -> remote.get("note") == null, WITHIN);
        assertThat(fieldKeys(send(admin, get("/api/v1/query-meta/ms.notes"), null)))
                .doesNotContain(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> fieldKeys(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return ((List<Map<String, Object>>) object(response).get("fields"))
                .stream().map(field -> (String) field.get("key")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(MockHttpServletResponse response, String key) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return ((List<Map<String, Object>>) object(response).get("fields"))
                .stream()
                        .filter(field -> key.equals(field.get("key")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(key + " missing"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private static void awaitTrue(BooleanSupplier condition, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + within);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted", e);
            }
        }
    }

    private String user() {
        String login = "metacache-" + UUID.randomUUID().toString().substring(0, 8);
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
