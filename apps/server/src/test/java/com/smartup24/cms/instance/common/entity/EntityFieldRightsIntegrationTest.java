package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.markdown;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.searchable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 5.3, acceptance "a field-rights test covers form-meta, read, write and export" (ADR-0032, 5.2),
 * end to end: an entity over the note table whose text needs a right the role {@code user} lacks, and whose colour only
 * holders of that right change. For a person without it the text does not exist — {@code form-meta},
 * {@code query-meta}, the export (its columns and its rows, read as the list reads them) and the history leave it out,
 * and asking the export for its column is refused as for an unknown one — while the colour is read-only. A holder of
 * the right sees and writes both. The general runtime serves the entity like any other (ADR-0032, 6; plan 10/10, item
 * 5.4): its history and export read its records through the runtime. The write itself is refused by
 * {@code EntityFieldRights}, covered in {@link EntityFieldRightsTest} and by the entity contract kit.
 */
@Import(EntityFieldRightsIntegrationTest.Fixture.class)
class EntityFieldRightsIntegrationTest extends EmbeddedPostgresTest {

    static final String CODE = "ms.secretnotes";
    static final String SECRET_FORM = "md.modules";
    static final String SECRET_ACTION = "view";

    private static final String PASSWORD = "StrongPassword2026!";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The note table seen through a second declaration: the text needs a right, the colour another to change. */
    @TestConfiguration
    static class Fixture {

        static final EntityDefinition SECRET = Entity.define(CODE, "notes")
                .table("ms_notes", "n")
                .scope(EntityScope.owner("created_by"))
                .field(text("title", "notes.col.title")
                        .column("title")
                        .required()
                        .list(sortable().searchable()))
                .field(markdown("contentMd", "notes.col.content")
                        .column("content_md")
                        .requires(SECRET_FORM, SECRET_ACTION)
                        .list(searchable()))
                .field(select("color", "notes.col.color", MsNoteEntity.COLORS, "notes.color_")
                        .column("color")
                        .readonlyUnless(SECRET_FORM, SECRET_ACTION))
                .field(instant("modifiedAt", "notes.col.modified_at")
                        .system(SystemColumn.MODIFIED_AT)
                        .list(sortable()))
                .section("main", "entity.section.main", "title", "contentMd")
                .section("settings", "entity.section.settings", "color")
                .defaultSort("modifiedAt", Entity.Sort.DESC)
                .capabilities(EntityCapability.EXPORT, EntityCapability.HISTORY)
                .build();

        @Bean
        EntityDefinition secretNotesEntity() {
            return SECRET;
        }
    }

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
        assertThat(holds("chief_admin"))
                .as("the chief administrator holds the right")
                .isTrue();
        assertThat(holds("user")).as("the role user lacks the right").isFalse();
    }

    @Test
    @DisplayName(
            "5.3: form-meta and query-meta leave the field out for a viewer without its right and mark the read-only")
    void metadataFollowsTheFieldRights() throws Exception {
        Session plain = login(user("user"));
        Session holder = login(user("chief_admin"));

        Map<String, Object> form = object(send(plain, get("/api/v1/form-meta/" + CODE), null));
        assertThat(fields(form)).extracting(field -> field.get("key")).containsExactly("title", "color");
        assertThat(fields(form)).extracting(field -> field.get("readonly")).containsExactly(false, true);
        assertThat(object(send(plain, get("/api/v1/query-meta/" + CODE), null)).toString())
                .doesNotContain("contentMd");

        Map<String, Object> full = object(send(holder, get("/api/v1/form-meta/" + CODE), null));
        assertThat(fields(full)).extracting(field -> field.get("key")).containsExactly("title", "contentMd", "color");
        assertThat(fields(full)).extracting(field -> field.get("readonly")).containsOnly(false);
        assertThat(object(send(holder, get("/api/v1/query-meta/" + CODE), null)).toString())
                .contains("contentMd");
    }

    @Test
    @DisplayName("5.3: the export and its rows leave the field out, and asking for its column is refused")
    void theExportLeavesTheFieldOut() throws Exception {
        jdbc.sql("delete from fnd_job_queue").update();
        String tag = "frx" + UUID.randomUUID().toString().substring(0, 8);
        Session plain = login(user("user"));
        Session holder = login(user("chief_admin"));
        note(plain, tag + " plain", "secret of plain");
        note(holder, tag + " holder", "secret of holder");

        List<List<String>> plainRows = export(plain, tag);
        List<List<String>> holderRows = export(holder, tag);

        assertThat(plainRows).hasSize(2);
        assertThat(plainRows.toString()).contains(tag + " plain").doesNotContain("secret of");
        assertThat(holderRows).hasSize(2);
        assertThat(holderRows.toString()).contains(tag + " holder").contains("secret of holder");
        assertThat(holderRows.getFirst()).hasSize(plainRows.getFirst().size() + 1);

        MockHttpServletResponse asked = send(
                plain,
                post("/api/v1/exports"),
                Map.of("list", CODE, "q", tag, "columns", List.of("title", "contentMd"), "lang", "en"));
        assertThat(asked.getStatus()).isEqualTo(422);
        assertThat(asked.getContentAsString()).contains("columns[1]");
    }

    @Test
    @DisplayName("5.3: the history hides the field from a viewer without its right")
    void theHistoryHidesTheField() throws Exception {
        String login = user("user");
        Session plain = login(login);
        long id = note(plain, "History probe", "secret text");
        String plainHistory =
                send(plain, get("/api/v1/history/" + CODE + "/" + id), null).getContentAsString(StandardCharsets.UTF_8);
        assertThat(plainHistory)
                .contains("\"field\":\"title\"")
                .doesNotContain("contentMd")
                .doesNotContain("secret");

        // The same rows under the note's own declaration, which restricts nothing, show it.
        String notesHistory =
                send(plain, get("/api/v1/history/ms.notes/" + id), null).getContentAsString(StandardCharsets.UTF_8);
        assertThat(notesHistory).contains("\"field\":\"contentMd\"");
    }

    private List<List<String>> export(Session s, String tag) throws Exception {
        MockHttpServletResponse queued = send(s, post("/api/v1/exports"), Map.of("list", CODE, "q", tag, "lang", "en"));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        String id = String.valueOf(object(queued).get("id"));
        jobs.runQueued();
        MockHttpServletResponse file = send(s, get("/api/v1/exports/" + id + "/file"), null);
        assertThat(file.getStatus()).as(file.getContentAsString()).isEqualTo(200);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            return workbook.getFirstSheet().read().stream()
                    .map(EntityFieldRightsIntegrationTest::cells)
                    .toList();
        }
    }

    private static List<String> cells(Row row) {
        return row.stream().map(cell -> cell == null ? "" : cell.getText()).toList();
    }

    private boolean holds(String role) {
        return jdbc.sql("""
                        select exists (select 1 from md_role_permissions p join md_roles r on r.id = p.role_id
                                       where r.pcode = :role and p.form_code = :form and p.action = :action)
                        """)
                .param("role", role)
                .param("form", SECRET_FORM)
                .param("action", SECRET_ACTION)
                .query(Boolean.class)
                .single();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> form) {
        return (List<Map<String, Object>>) form.get("fields");
    }

    private long note(Session s, String title, String content) throws Exception {
        MockHttpServletResponse created =
                send(s, post("/api/v1/entities/ms.notes"), Map.of("title", title, "contentMd", content));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) object(created).get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isLessThan(300);
        return JSON.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private String user(String role) {
        String login = "rights-" + UUID.randomUUID().toString().substring(0, 8);
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
