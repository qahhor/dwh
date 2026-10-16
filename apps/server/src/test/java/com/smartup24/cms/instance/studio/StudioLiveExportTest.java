package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.report.export.ReportExportJob;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A live form becomes available to an existing background exporter without losing isolation (ADR-0038). */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioLiveExportTest extends EmbeddedPostgresTest {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ReportExportJob exportJob;

    @Test
    void activatedFormExportsOnlyItsRowsAndRevocationClosesTheExistingFile() throws Exception {
        var users = TestUsers.of(context);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long unit = users.unit("live-export-" + tag);
        var publisher = TestSession.signIn(
                context,
                users.withRights(Map.of("studio.forms", Set.of("publish"), "md.settings", Set.of("update")), unit)
                        .login());
        String own = activate(publisher, "export_" + tag);
        String other = activate(publisher, "foreign_" + tag);
        var writer = TestSession.signIn(
                context,
                users.withRights(Map.of(own, Set.of("view", "create"), other, Set.of("view", "create")), unit)
                        .login());
        create(writer, own, "Own export marker", 10);
        create(writer, other, "Foreign export marker", 999);
        var viewer = users.withRights(Map.of(own, Set.of("view")), unit);
        var session = TestSession.signIn(context, viewer.login());
        var queued = session.send(
                post("/api/v1/exports"), Map.of("list", own, "columns", List.of("name", "capacity"), "lang", "en"));
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        var item = TestSession.object(queued);
        assertThat(item).containsEntry("state", "queued");
        String id = (String) item.get("id");

        runQueuedExport(id);

        var journal = session.send(get("/api/v1/exports"));
        assertThat(journal.getStatus()).isEqualTo(200);
        assertThat(json.readTree(journal.getContentAsString())
                        .get(0)
                        .path("state")
                        .asString())
                .isEqualTo("done");
        assertThat(json.readTree(journal.getContentAsString())
                        .get(0)
                        .path("rowsCount")
                        .asInt())
                .isEqualTo(1);
        assertWorkbook(session, id);
        assertModuleSwitchClosesFile(session, id);
        revoke(viewer.id(), own);
        assertThat(session.send(get("/api/v1/exports/" + id + "/file")).getStatus())
                .isEqualTo(403);
    }

    private String activate(TestSession publisher, String name) throws Exception {
        ObjectNode declaration =
                (ObjectNode) json.readTree(Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json")));
        ((ObjectNode) declaration.path("module")).put("code", "studio");
        ((ObjectNode) declaration.path("entity")).put("name", name);
        for (var field : declaration.path("fields")) {
            if (field.path("id").asString().equals("capacity")) {
                ((ObjectNode) field.path("labels")).put("en", "Live capacity");
            }
        }
        var prepared = publisher.send(post("/api/v1/form-studio/new-forms/prepare"), declaration, null);
        assertThat(prepared.getStatus()).as(prepared.getContentAsString()).isEqualTo(200);
        var activated = publisher.send(post("/api/v1/studio/forms"), TestSession.object(prepared), null);
        assertThat(activated.getStatus()).as(activated.getContentAsString()).isEqualTo(201);
        return "studio." + name;
    }

    private void create(TestSession writer, String code, String name, int capacity) throws Exception {
        var created = writer.send(
                post("/api/v1/entities/" + code),
                Map.of("name", name, "code", "shared-code", "address", "Test address", "capacity", capacity));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    }

    private void runQueuedExport(String id) throws Exception {
        String raw = jdbc.sql("""
                        select args::text from fnd_job_queue
                         where handler = 'report.export' and args ->> 'exportId' = :id
                        """).param("id", id).query(String.class).single();
        Map<String, Object> args = json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
                        assertThat(SecurityContext.isAuthenticated()).isFalse();
                        exportJob.run(args);
                        assertThat(SecurityContext.isAuthenticated()).isFalse();
                    })
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private void assertWorkbook(TestSession session, String id) throws Exception {
        var file = session.send(get("/api/v1/exports/" + id + "/file"));
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentType()).contains("spreadsheetml.sheet");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).getCellText(1)).isEqualTo("Live capacity");
            assertThat(rows.get(1).getCellText(0)).isEqualTo("Own export marker");
            assertThat(new BigDecimal(rows.get(1).getCellText(1))).isEqualByComparingTo("10");
        }
    }

    private void assertModuleSwitchClosesFile(TestSession session, String id) throws Exception {
        var modules = context.getBean(ModuleRegistryService.class);
        modules.toggleModuleStatus("studio", false);
        try {
            assertThat(session.send(get("/api/v1/exports/" + id + "/file")).getStatus())
                    .isEqualTo(403);
        } finally {
            modules.toggleModuleStatus("studio", true);
        }
        assertThat(session.send(get("/api/v1/exports/" + id + "/file")).getStatus())
                .isEqualTo(200);
    }

    private void revoke(long userId, String form) {
        jdbc.sql("""
                        delete from md_role_permissions where form_code = :form
                          and role_id in (select role_id from md_user_roles where user_id = :user)
                        """).param("form", form).param("user", userId).update();
        context.getBean(MdPermissionService.class).recalculateEffectivePermissions(userId);
    }
}
