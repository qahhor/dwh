package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 5.1 (ADR-0032, 3.5): the answers of {@code GET /api/v1/form-meta/ms.notes} and
 * {@code GET /api/v1/query-meta/ms.notes} stay byte for byte what they were after item 5.0, whatever the server builds
 * them from. The snapshots live in {@code src/test/resources/entity-meta}; an intended change is written with
 * {@code -Dentity.meta.update=true} and recorded in the CHANGELOG.
 */
class EntityMetaSnapshotTest extends EmbeddedPostgresTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/entity-meta");
    private static final String PASSWORD = "StrongPassword2026!";
    private static final ObjectMapper JSON = new ObjectMapper();

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

    @ParameterizedTest(name = "{0}/ms.notes")
    @ValueSource(strings = {"form-meta", "query-meta"})
    void notesMetadataIsTheSnapshot(String endpoint) throws Exception {
        assertThat(jdbc.sql("select count(*) from md_custom_fields where entity_type = 'NOTE'")
                        .query(Long.class)
                        .single())
                .as("the snapshot holds the declared fields only")
                .isZero();
        Cookie session = login(administrator());
        MockHttpServletResponse response = mvc.perform(
                        get("/api/v1/" + endpoint + "/ms.notes").cookie(session))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        Path snapshot = SNAPSHOTS.resolve("ms.notes." + endpoint + ".json");
        if (Boolean.getBoolean("entity.meta.update")) {
            Files.createDirectories(SNAPSHOTS);
            Files.writeString(snapshot, body + "\n", StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(snapshot))
                .as("run with -Dentity.meta.update=true to write " + snapshot)
                .isTrue();
        assertThat(body)
                .as(
                        "the %s answer changed: an intended change takes -Dentity.meta.update=true and a CHANGELOG line",
                        endpoint)
                .isEqualTo(Files.readString(snapshot, StandardCharsets.UTF_8).strip());
    }

    /** A chief administrator: every action of the notes right, so the form lists them all. */
    private String administrator() {
        String login = "metasnap-" + UUID.randomUUID().toString().substring(0, 8);
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

    private Cookie login(String login) throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(JSON.writeValueAsString(
                                Map.of("login", login, "password", PASSWORD, "deviceInfo", "test"))))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getCookie(KauthPref.SESSION_COOKIE_NAME);
    }
}
