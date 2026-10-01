package com.smartup24.cms.instance.ms.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 5.0: another person's note answers exactly as a note that does not exist, 404, on every path of
 * the entity — read, change, pin, delete, history, bulk delete and export — so no answer tells that the id exists.
 */
class MsNoteForeignRecordIntegrationTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MISSING = 9_000_000_000L;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MdUserService users;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JobRunner jobs;

    private MockMvc mvc;

    private record Session(Cookie session, Cookie csrf) {}

    /** A path of the entity, for one note id. */
    private record Path(String name, LongFunction<MockHttpServletRequestBuilder> request, Object body) {
        @Override
        public String toString() {
            return name;
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    static Stream<Arguments> paths() {
        return Stream.of(
                        new Path("get", id -> get("/api/v1/notes/" + id), null),
                        new Path(
                                "update",
                                id -> put("/api/v1/notes/" + id).header("If-Match", "\"1\""),
                                Map.of("title", "x")),
                        new Path("delete", id -> delete("/api/v1/notes/" + id), null),
                        new Path("pin", id -> put("/api/v1/notes/" + id + "/pin"), Map.of("pinned", true)),
                        new Path("toggle pin", id -> post("/api/v1/notes/" + id + "/pin"), null),
                        new Path("history", id -> get("/api/v1/history/ms.notes/" + id), null))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    @DisplayName("5.0: another person's note is 404 on the path, the same answer as a note that does not exist")
    void foreignNoteIsNotFound(Path path) throws Exception {
        Session owner = login(user());
        Session other = login(user());
        long id = note(owner, "Private " + path.name());

        MockHttpServletResponse foreign = send(other, path.request().apply(id), path.body());
        MockHttpServletResponse missing = send(other, path.request().apply(MISSING), path.body());

        assertThat(foreign.getStatus()).as(foreign.getContentAsString()).isEqualTo(404);
        assertThat(problem(foreign)).isEqualTo(problem(missing));
        assertThat(problem(foreign).get("messageKey")).isEqualTo("error.note.not_found");
        // The owner's note is untouched.
        MockHttpServletResponse own = send(owner, get("/api/v1/notes/" + id), null);
        assertThat(own.getStatus()).isEqualTo(200);
        assertThat(object(own))
                .containsEntry("title", "Private " + path.name())
                .containsEntry("isPinned", false)
                .containsEntry("revision", 1);
    }

    @Test
    @DisplayName("5.0: a bulk delete reports another person's note as not found, like a missing one, and keeps it")
    void bulkDeleteTreatsAForeignNoteAsMissing() throws Exception {
        Session owner = login(user());
        Session other = login(user());
        long id = note(owner, "Private bulk");

        MockHttpServletResponse bulk = send(
                other, post("/api/v1/entities/ms.notes/bulk"), Map.of("action", "delete", "ids", List.of(id, MISSING)));

        assertThat(bulk.getStatus()).as(bulk.getContentAsString()).isEqualTo(200);
        Map<String, Object> result = object(bulk);
        assertThat(result).containsEntry("succeeded", 0).containsEntry("failed", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("results");
        assertThat(items)
                .extracting(item -> item.get("code") + " " + item.get("messageKey"))
                .containsExactly("not_found error.note.not_found", "not_found error.note.not_found");
        assertThat(send(owner, get("/api/v1/notes/" + id), null).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("5.0: an export of the note list holds only the requester's notes")
    void exportHoldsOnlyOwnNotes() throws Exception {
        jdbc.sql("delete from fnd_job_queue").update();
        String tag = "exp" + UUID.randomUUID().toString().substring(0, 8);
        Session owner = login(user());
        Session other = login(user());
        note(owner, tag + " owner");
        note(other, tag + " mine");

        MockHttpServletResponse queued =
                send(other, post("/api/v1/exports"), Map.of("list", "ms.notes", "q", tag, "lang", "en"));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        jobs.runQueued();

        MockHttpServletResponse journal = send(other, get("/api/v1/exports"), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                JSON.readValue(journal.getContentAsString(StandardCharsets.UTF_8), List.class);
        assertThat(entries.getFirst()).containsEntry("state", "done").containsEntry("rowsCount", 1);
    }

    private long note(Session s, String title) throws Exception {
        MockHttpServletResponse created = send(s, post("/api/v1/notes"), Map.of("title", title));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) object(created).get("id")).longValue();
    }

    /** What tells one refusal from another: the status, the code and the message key, not the id in the text. */
    private static Map<String, Object> problem(MockHttpServletResponse response) throws Exception {
        Map<String, Object> body = object(response);
        return Map.of(
                "status", response.getStatus(),
                "code", String.valueOf(body.get("code")),
                "messageKey", String.valueOf(body.get("messageKey")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private String user() {
        String login = "foreign-" + UUID.randomUUID().toString().substring(0, 8);
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
