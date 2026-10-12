package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.markdown;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.searchable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.md.api.MdAssignmentDtos.GrantDto;
import com.smartup24.cms.instance.md.service.MdAssignmentService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/** ADR-0032: a revoked field right applies to live reads and every retained copy of a response. */
@Import(FieldAccessRevocationIntegrationTest.Fixture.class)
class FieldAccessRevocationIntegrationTest extends EmbeddedPostgresTest {

    private static final String CODE = "ms.accessnotes";
    private static final String BASE = "/api/v1/entities/" + CODE;
    private static final String SECRET = "restricted-content-" + UUID.randomUUID();
    private static final Map<String, Object> BODY =
            Map.of("title", "Access probe", "contentMd", SECRET, "color", "blue");

    @TestConfiguration
    static class Fixture {
        @Bean
        EntityDefinition accessNotes() {
            return Entity.define(CODE, "notes")
                    .table("ms_notes", "n")
                    .scope(EntityScope.owner("created_by"))
                    .field(text("title", "notes.col.title")
                            .column("title")
                            .required()
                            .list(sortable().searchable()))
                    .field(markdown("contentMd", "notes.col.content")
                            .column("content_md")
                            .requires("md.modules", "view")
                            .list(searchable()))
                    .field(select("color", "notes.col.color", List.of("blue", "red"), null)
                            .column("color")
                            .readonlyUnless("md.modules", "view"))
                    .section("main", "entity.section.main", "title", "contentMd", "color")
                    .actions("create", "update")
                    .defaultSort("title", Entity.Sort.ASC)
                    .capabilities(EntityCapability.EXPORT, EntityCapability.HISTORY)
                    .build();
        }
    }

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MdAssignmentService assignments;

    @Autowired
    private JobRunner jobs;

    @Autowired
    private ModuleRegistryService modules;

    private TestUsers.TestUser actor;
    private TestSession session;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("delete from fnd_job_queue").update();
        TestUsers users = TestUsers.of(wac);
        actor = users.withRights(Map.of("notes", List.of("view", "create", "update")), users.unit("fields"));
        rights(List.of(new GrantDto("md.modules", "view")));
        session = TestSession.signIn(wac, actor.login());
    }

    @Test
    void revokingFieldRightImmediatelyHidesReadsHistoryAndSearchAndRefusesWrites() throws Exception {
        var created = create(UUID.randomUUID());
        long id = ((Number) TestSession.object(created).get("id")).longValue();
        String path = BASE + "/" + id;
        assertThat(session.send(get(path)).getContentAsString()).contains(SECRET);
        assertThat(session.send(get("/api/v1/form-meta/" + CODE)).getContentAsString())
                .contains("contentMd");
        assertThat(session.send(get("/api/v1/history/" + CODE + "/" + id)).getContentAsString())
                .contains(SECRET);
        assertThat(session.send(get(BASE).param("q", SECRET)).getContentAsString())
                .contains(SECRET);

        rights(List.of());

        for (String endpoint : List.of(
                path,
                BASE,
                "/api/v1/form-meta/" + CODE,
                "/api/v1/query-meta/" + CODE,
                "/api/v1/history/" + CODE + "/" + id)) {
            var response = session.send(get(endpoint));
            assertThat(response.getStatus()).as(endpoint).isEqualTo(200);
            assertThat(response.getContentAsString()).doesNotContain("contentMd", SECRET);
        }
        assertThat(TestSession.object(session.send(get(BASE).param("q", SECRET)))
                        .get("items"))
                .isEqualTo(List.of());
        assertThat(session.send(get(BASE)
                                .param("filter", "[{\"field\":\"contentMd\",\"op\":\"eq\",\"value\":\"probe\"}]"))
                        .getStatus())
                .isEqualTo(422);
        var hiddenWrite = session.send(patch(path).header("If-Match", "\"1\""), Map.of("contentMd", "changed"));
        assertThat(hiddenWrite.getStatus()).as(hiddenWrite.getContentAsString()).isEqualTo(422);
        assertThat(hiddenWrite.getContentAsString()).contains("unknown_field").doesNotContain(SECRET);
        var readonlyWrite = session.send(patch(path).header("If-Match", "\"1\""), Map.of("color", "red"));
        assertThat(readonlyWrite.getStatus())
                .as(readonlyWrite.getContentAsString())
                .isEqualTo(422);
        assertThat(readonlyWrite.getContentAsString()).contains("readonly");
        var allowed = session.send(patch(path).header("If-Match", "\"1\""), Map.of("title", "Allowed change"));
        assertThat(allowed.getStatus()).as(allowed.getContentAsString()).isEqualTo(200);
        assertThat(jdbc.sql("select content_md from ms_notes where id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo(SECRET);
    }

    @Test
    void revokedFieldCannotBeRecoveredFromAnIdempotentResponse() throws Exception {
        UUID key = UUID.randomUUID();
        var first = create(key);
        var replay = session.send(post(BASE), BODY, key);
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.getContentAsString()).contains(SECRET);

        rights(List.of());

        var refused = session.send(post(BASE), BODY, key);
        assertThat(refused.getStatus())
                .as("old response must not bypass field revocation")
                .isEqualTo(409);
        assertThat(refused.getContentAsString()).doesNotContain(SECRET);
        assertThat(refused.getHeader("Idempotent-Replay")).isNull();
        assertThat(jdbc.sql("select count(*) from ms_notes where created_by = :id")
                        .param("id", actor.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(TestSession.object(first).get("id")).isNotNull();
    }

    @Test
    void readyExportCannotRevealARevokedField() throws Exception {
        create(UUID.randomUUID());
        String export = queueExport();
        jobs.runQueued();
        assertThat(workbook(session.send(get(export)))).contains(SECRET);

        rights(List.of());

        var refused = session.send(get(export));
        assertThat(refused.getStatus())
                .as("ready file must respect field revocation")
                .isEqualTo(403);
        assertThat(refused.getContentType()).startsWith("application/problem+json");
        assertThat(refused.getContentAsString()).doesNotContain(SECRET);
    }

    @Test
    void disablingTheModuleClosesRetainedRecordsAndReadyExports() throws Exception {
        UUID key = UUID.randomUUID();
        create(key);
        String export = queueExport();
        jobs.runQueued();
        assertThat(workbook(session.send(get(export)))).contains(SECRET);
        modules.toggleModuleStatus("notes", false);
        try {
            assertThat(session.send(get(BASE)).getStatus()).isEqualTo(404);
            assertThat(session.send(get(export)).getStatus()).isEqualTo(403);
            var replay = session.send(post(BASE), BODY, key);
            assertThat(replay.getStatus()).isEqualTo(409);
            assertThat(replay.getContentAsString()).doesNotContain(SECRET);
        } finally {
            modules.toggleModuleStatus("notes", true);
        }
    }

    @Test
    void queuedExportUsesTheRevokedFieldRightsAndStillExportsVisibleColumns() throws Exception {
        create(UUID.randomUUID());
        String export = queueExport();
        rights(List.of());

        jobs.runQueued();

        assertThat(workbook(session.send(get(export)))).contains("Access probe").doesNotContain(SECRET);
    }

    private MockHttpServletResponse create(UUID key) throws Exception {
        var response = session.send(post(BASE), BODY, key);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return response;
    }

    private String queueExport() throws Exception {
        var response = session.send(post("/api/v1/exports"), Map.of("list", CODE, "lang", "en"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
        return "/api/v1/exports/" + TestSession.object(response).get("id") + "/file";
    }

    private static String workbook(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as("export status").isEqualTo(200);
        try (ReadableWorkbook workbook =
                new ReadableWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            return workbook.getFirstSheet().read().stream()
                    .flatMap(row -> row.stream())
                    .map(cell -> cell == null ? "" : cell.getText())
                    .toList()
                    .toString();
        }
    }

    private void rights(List<GrantDto> grants) {
        long revision = jdbc.sql("select revision from md_users where id = :id")
                .param("id", actor.id())
                .query(Long.class)
                .single();
        assignments.replacePersonalPermissions(actor.id(), grants, revision);
    }
}
