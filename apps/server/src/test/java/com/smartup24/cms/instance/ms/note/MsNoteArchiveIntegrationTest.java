package com.smartup24.cms.instance.ms.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Plan 10/10, item 5.3 (ADR-0032, 5.3–5.4): an archived note leaves the default list — the list a reference picks
 * from too — the search and the export, is listed again with the {@code archived} filter, and still reads by id with
 * {@code archived: true}, so an old reference to it resolves with its mark. Archiving is a switch that names its
 * revision: a stale one is 409, a delete from a stale revision too.
 */
class MsNoteArchiveIntegrationTest extends EmbeddedPostgresTest {

    private static final String PASSWORD = "StrongPassword2026!";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ARCHIVED_ONLY = "[{\"field\":\"archived\",\"op\":\"eq\",\"value\":true}]";
    private static final String IN_USE_ONLY = "[{\"field\":\"archived\",\"op\":\"eq\",\"value\":false}]";

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

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("5.3: an archived note leaves the list and the search, is listed by the filter and reads by id")
    void anArchivedNoteLeavesTheListAndStillReads() throws Exception {
        Session s = login(user());
        String tag = "arc" + UUID.randomUUID().toString().substring(0, 8);
        long archived = note(s, tag + " old");
        long kept = note(s, tag + " kept");

        MockHttpServletResponse done = send(
                s,
                put("/api/v1/entities/ms.notes/" + archived + "/archived").header("If-Match", "\"1\""),
                Map.of("archived", true));
        assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
        assertThat(done.getHeader("ETag")).isEqualTo("\"2\"");
        assertThat(object(done)).containsEntry("archived", true).containsEntry("revision", 2);
        assertThat(object(done).get("archivedAt")).isNotNull();
        assertThat(jdbc.sql("select archived_by is not null from ms_notes where id = :id")
                        .param("id", archived)
                        .query(Boolean.class)
                        .single())
                .isTrue();

        // The default list — and the paged list a reference picks from — leaves it out.
        assertThat(ids(send(s, get("/api/v1/entities/ms.notes").param("q", tag), null)))
                .containsExactly(kept);
        assertThat(ids(send(s, get("/api/v1/entities/ms.notes").param("q", tag).param("filter", ARCHIVED_ONLY), null)))
                .containsExactly(archived);
        assertThat(ids(send(s, get("/api/v1/entities/ms.notes").param("q", tag).param("filter", IN_USE_ONLY), null)))
                .containsExactly(kept);

        // A read by id still answers, with its mark: an old reference resolves.
        MockHttpServletResponse read = send(s, get("/api/v1/entities/ms.notes/" + archived), null);
        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(object(read)).containsEntry("archived", true).containsEntry("title", tag + " old");
        assertThat(object(send(s, get("/api/v1/entities/ms.notes/" + kept), null)))
                .containsEntry("archived", false);

        // The global search reads the published view, which leaves archived notes out.
        assertThat(jdbc.sql("select count(*) from ms_note_pub_notes where id in (:ids)")
                        .param("ids", List.of(archived, kept))
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);

        // The history names the change.
        MockHttpServletResponse history = send(s, get("/api/v1/history/ms.notes/" + archived), null);
        assertThat(history.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"field\":\"archived\"")
                .contains("entity.col.archived");
    }

    @Test
    @DisplayName("5.3: archiving names its revision, changes only once, and restores the note to the list")
    void archivingIsASwitchThatNamesItsRevision() throws Exception {
        Session s = login(user());
        String tag = "sw" + UUID.randomUUID().toString().substring(0, 8);
        long id = note(s, tag);
        String path = "/api/v1/entities/ms.notes/" + id + "/archived";

        assertThat(send(s, put(path), Map.of("archived", true)).getStatus()).isEqualTo(428);
        assertThat(send(s, put(path).header("If-Match", "\"1\""), Map.of("archived", true))
                        .getStatus())
                .isEqualTo(200);
        // The same state again from the current revision: nothing is written, the note answers as it is.
        MockHttpServletResponse again = send(s, put(path).header("If-Match", "\"2\""), Map.of("archived", true));
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(object(again)).containsEntry("revision", 2);
        assertThat(auditRows(id)).isEqualTo(2);
        // A stale revision is refused, whatever it asks.
        MockHttpServletResponse stale = send(s, put(path).header("If-Match", "\"1\""), Map.of("archived", false));
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(object(stale)).containsEntry("code", "revision_conflict");

        MockHttpServletResponse restored = send(s, put(path).header("If-Match", "\"2\""), Map.of("archived", false));
        assertThat(restored.getStatus()).isEqualTo(200);
        assertThat(object(restored)).containsEntry("archived", false).containsEntry("revision", 3);
        assertThat(object(restored).get("archivedAt")).isNull();
        assertThat(ids(send(s, get("/api/v1/entities/ms.notes").param("q", tag), null)))
                .containsExactly(id);
    }

    @Test
    @DisplayName("5.3: a delete from a stale revision is 409; from the current one, or naming none, it deletes")
    void aDeleteFromAStaleRevisionIsAConflict() throws Exception {
        Session s = login(user());
        long id = note(s, "Delete probe");
        assertThat(send(
                                s,
                                patch("/api/v1/entities/ms.notes/" + id).header("If-Match", "\"1\""),
                                Map.of("title", "Changed"))
                        .getStatus())
                .isEqualTo(200);

        MockHttpServletResponse stale =
                send(s, delete("/api/v1/entities/ms.notes/" + id).header("If-Match", "\"1\""), null);
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(send(s, get("/api/v1/entities/ms.notes/" + id), null).getStatus())
                .isEqualTo(200);
        assertThat(send(s, delete("/api/v1/entities/ms.notes/" + id).header("If-Match", "\"2\""), null)
                        .getStatus())
                .isEqualTo(204);
        long other = note(s, "Delete probe 2");
        assertThat(send(s, delete("/api/v1/entities/ms.notes/" + other), null).getStatus())
                .isEqualTo(204);
    }

    @Test
    @DisplayName("5.3: the bulk archive archives the owner's notes, and the export follows the list")
    void bulkArchiveAndExportFollowTheList() throws Exception {
        jdbc.sql("delete from fnd_job_queue").update();
        Session s = login(user());
        String tag = "bx" + UUID.randomUUID().toString().substring(0, 8);
        long archived = note(s, tag + " a");
        note(s, tag + " b");

        MockHttpServletResponse bulk =
                send(s, post("/api/v1/entities/ms.notes/bulk"), Map.of("action", "archive", "ids", List.of(archived)));
        assertThat(bulk.getStatus()).as(bulk.getContentAsString()).isEqualTo(200);
        assertThat(object(bulk)).containsEntry("succeeded", 1).containsEntry("failed", 0);
        assertThat(object(send(s, get("/api/v1/entities/ms.notes/" + archived), null)))
                .containsEntry("archived", true);

        MockHttpServletResponse queued =
                send(s, post("/api/v1/exports"), Map.of("list", "ms.notes", "q", tag, "lang", "en"));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        jobs.runQueued();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> journal = JSON.readValue(
                send(s, get("/api/v1/exports"), null).getContentAsString(StandardCharsets.UTF_8), List.class);
        assertThat(journal.getFirst()).containsEntry("state", "done").containsEntry("rowsCount", 1);
    }

    private int auditRows(long id) {
        return jdbc.sql("select count(*) from audit_log where table_name = 'ms_notes' and row_pk = :id")
                .param("id", String.valueOf(id))
                .query(Integer.class)
                .single();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ids(MockHttpServletResponse page) throws Exception {
        assertThat(page.getStatus()).as(page.getContentAsString()).isEqualTo(200);
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) object(page).get("items");
        return items.stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }

    private long note(Session s, String title) throws Exception {
        MockHttpServletResponse created = send(s, post("/api/v1/entities/ms.notes"), Map.of("title", title));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) object(created).get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(MockHttpServletResponse response) throws Exception {
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private String user() {
        String login = "archive-" + UUID.randomUUID().toString().substring(0, 8);
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
        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/login")
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
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isNotEqualTo(500);
        return response;
    }
}
