package com.smartup24.cms.instance.audit;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the JSON property sets of the audit stats, audit log rows and security events. Null properties are left
 * out ({@code non_null}), so a row carries the always-present properties and nothing outside the full set.
 */
class AuditWireFormatTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";

    private static final Set<String> PAGE = Set.of("items", "hasMore", "totalEstimated", "totalExact");

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MdUserService users;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("stats, audit log rows and security events keep their properties")
    void auditResponsesKeepTheirProperties() throws Exception {
        String login = user("chief_admin");
        Cookie session = login(login);
        long userId = jdbc.sql("select id from md_users where login = :login")
                .param("login", login)
                .query(Long.class)
                .single();

        JsonNode stats = fetch(session, "/api/v1/audit/stats");
        assertThat(keys(stats))
                .isEqualTo(Set.of(
                        "totalAuditLogs",
                        "totalSecurityEvents",
                        "securityEventsLast24h",
                        "failedLoginsLast24h",
                        "computedAt"));

        JsonNode logs = fetch(session, "/api/v1/audit/logs?tableName=md_users&rowPk=" + userId + "&limit=5");
        assertThat(keys(logs)).containsAll(PAGE).isSubsetOf(union(PAGE, Set.of("nextCursor")));
        assertThat(logs.get("items").size()).isPositive();
        for (JsonNode row : logs.get("items")) {
            assertThat(keys(row))
                    .containsAll(Set.of("id", "tableName", "rowPk", "event", "isApi", "changedAt"))
                    .isSubsetOf(Set.of(
                            "id",
                            "tableName",
                            "rowPk",
                            "event",
                            "changedBy",
                            "sessionId",
                            "isApi",
                            "changedAt",
                            "changedColumns",
                            "oldRow",
                            "newRow",
                            "changedByName",
                            "changedByLogin"));
        }

        JsonNode events = fetch(session, "/api/v1/audit/security-events?userId=" + userId + "&limit=5");
        assertThat(keys(events)).containsAll(PAGE).isSubsetOf(union(PAGE, Set.of("nextCursor")));
        assertThat(events.get("items").size()).isPositive();
        for (JsonNode event : events.get("items")) {
            assertThat(keys(event))
                    .containsAll(Set.of("id", "eventType", "userId", "createdAt"))
                    .isSubsetOf(Set.of(
                            "id",
                            "eventType",
                            "userId",
                            "ip",
                            "userAgent",
                            "details",
                            "createdAt",
                            "userName",
                            "userLogin"));
        }
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

    private JsonNode fetch(Cookie session, String url) throws Exception {
        var response = mvc.perform(get(java.net.URI.create(url)).cookie(session))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return new ObjectMapper().readTree(response.getContentAsString());
    }

    private String user(String role) {
        String login = "audit-wire-" + UUID.randomUUID().toString().substring(0, 8);
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

    private Cookie login(String login) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(new ObjectMapper()
                                .writeValueAsString(
                                        Map.of("login", login, "password", PASSWORD, "deviceInfo", "test"))))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getCookie(KauthPref.SESSION_COOKIE_NAME);
    }
}
