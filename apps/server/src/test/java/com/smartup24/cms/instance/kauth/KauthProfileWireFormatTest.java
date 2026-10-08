package com.smartup24.cms.instance.kauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.kauth.api.KauthPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the JSON property sets of the session, security summary, channel and API token responses, so a change of
 * the wire format (or a token hash or session key slipping into a response) fails here. Null properties are left
 * out by the server ({@code non_null}), so the data below fills every field that can be filled.
 */
class KauthProfileWireFormatTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";

    /** An active session has no {@code closedAt}; the null is left out. */
    private static final Set<String> SESSION =
            Set.of("id", "userId", "ip", "userAgent", "deviceInfo", "createdAt", "lastSeenAt");

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MdUserService users;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    private record Session(Cookie session, Cookie csrf, long userId, String login) {}

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("sessions: own list with the current flag, a user's list without it, the security summary")
    void sessionResponsesKeepTheirProperties() throws Exception {
        Session admin = login(user("chief_admin"));

        JsonNode own = json(send(admin, get("/api/v1/iam/profile/sessions")));
        assertThat(own.isArray()).isTrue();
        assertThat(keys(own.get(0))).isEqualTo(union(SESSION, Set.of("current")));

        JsonNode ofUser = json(send(admin, get("/api/v1/iam/users/{id}/sessions", admin.userId())));
        assertThat(keys(ofUser.get(0))).isEqualTo(SESSION);

        JsonNode summary = json(send(admin, get("/api/v1/iam/users/{id}/security", admin.userId())));
        // passwordChangedAt is always null today, so it is never on the wire
        assertThat(keys(summary))
                .isEqualTo(Set.of(
                        "userId",
                        "login",
                        "is2faEnabled",
                        "forcePasswordChange",
                        "createdAt",
                        "authVersion",
                        "activeSessionsCount",
                        "activeSessions",
                        "recentLoginAttempts"));
        assertThat(keys(summary.get("activeSessions").get(0))).isEqualTo(SESSION);
        JsonNode successfulLogin = summary.get("recentLoginAttempts").get(0);
        // a successful login has no failure reason
        assertThat(keys(successfulLogin)).isEqualTo(Set.of("id", "login", "ip", "isSuccess", "attemptAt"));
    }

    @Test
    @DisplayName("channels: the bound channel and the verification token of a new binding")
    void channelResponsesKeepTheirProperties() throws Exception {
        Session admin = login(user("chief_admin"));
        jdbc.sql("""
                        insert into kauth_user_channels (user_id, channel, address, is_verified, created_at)
                        values (:userId, 'email', 'wire@test.local', true, now())
                        """).param("userId", admin.userId()).update();

        JsonNode channels = json(send(admin, get("/api/v1/iam/profile/channels")));
        assertThat(keys(channels.get(0)))
                .isEqualTo(Set.of("id", "userId", "channel", "address", "isVerified", "createdAt"));

        MockHttpServletResponse bound = send(
                admin,
                post("/api/v1/iam/profile/channels"),
                Map.of("channel", "telegram", "address", "wire-" + admin.userId()));
        assertThat(bound.getStatus()).as(bound.getContentAsString()).isEqualTo(202);
        assertThat(keys(json(bound))).isEqualTo(Set.of("verifyToken"));
    }

    @Test
    @DisplayName("API tokens: the list carries no hash, the created token carries its one-time secret")
    void tokenResponsesKeepTheirProperties() throws Exception {
        Session admin = login(user("chief_admin"));

        MockHttpServletResponse created = send(
                admin, post("/api/v1/iam/profile/tokens"), Map.of("name", "wire", "expiresAt", "2099-01-01T00:00:00Z"));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        JsonNode result = json(created);
        assertThat(keys(result)).isEqualTo(Set.of("record", "rawSecretToken"));
        // never used and never revoked: lastUsedAt and revokedAt are null and left out
        Set<String> token = Set.of("id", "userId", "name", "tokenPrefix", "expiresAt", "createdAt");
        assertThat(keys(result.get("record"))).isEqualTo(token);

        JsonNode tokens = json(send(admin, get("/api/v1/iam/profile/tokens")));
        assertThat(keys(tokens.get(0))).isEqualTo(token);
    }

    private static Set<String> keys(JsonNode node) {
        assertThat(node).as("a JSON object").isNotNull();
        return Set.copyOf(node.propertyNames());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new java.util.HashSet<>(a);
        all.addAll(b);
        return all;
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return new ObjectMapper().readTree(response.getContentAsString());
    }

    private String user(String role) {
        String login = "wire-" + UUID.randomUUID().toString().substring(0, 8);
        Long systemId = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        Long roleId = jdbc.sql("select id from md_roles where pcode = :role")
                .param("role", role)
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
                        .header("User-Agent", "wire-test")
                        .contentType("application/json")
                        .content(new ObjectMapper()
                                .writeValueAsString(
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
        long userId = jdbc.sql("select id from md_users where login = :login")
                .param("login", login)
                .query(Long.class)
                .single();
        return new Session(session, csrf, userId, login);
    }

    private MockHttpServletResponse send(Session s, MockHttpServletRequestBuilder request) throws Exception {
        var response = send(s, request, null);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response;
    }

    private MockHttpServletResponse send(Session s, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        request.cookie(s.session(), s.csrf()).header("X-XSRF-TOKEN", s.csrf().getValue());
        if (body != null) {
            request.contentType("application/json").content(new ObjectMapper().writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isNotEqualTo(500);
        return response;
    }
}
