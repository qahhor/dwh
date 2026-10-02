package com.smartup24.cms.instance.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartup24.cms.instance.example.service.ExampleOrderEntity;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.ms.task.service.MsTaskTypeEntity;
import com.smartup24.cms.instance.report.imports.ReportImportService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.instance.support.entity.ImportFiles;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.context.WebApplicationContext;

/**
 * The import of entity records end to end (ADR-0032, 10.1; plan 10/10, item 5.8), beyond the kit's group: the template
 * with its hint sheets, a dry run that runs the hooks and leaves nothing — no record, no audit, no outbox row — an
 * upsert that the hooks complete, a key the server gives that a row cannot set, the problems of a whole file, the
 * checks of the request, the journal only its owner reads and the cleanup of an expired import.
 */
class ReportImportIntegrationTest extends EmbeddedPostgresTest {

    private static final String TYPES = MsTaskTypeEntity.CODE;
    private static final String ORDERS = ExampleOrderEntity.CODE;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ModuleRegistryService modules;

    @Autowired
    private ReportImportService imports;

    private ImportFiles files;
    private TestUser importer;
    private TestSession session;
    private String tag;

    @BeforeEach
    void setUp() throws Exception {
        modules.toggleModuleStatus("example", true);
        jdbc.sql("delete from fnd_job_queue").update();
        TestUsers users = TestUsers.of(wac);
        Set<String> all = Set.of("view", "create", "update", "import");
        importer = users.withRights(Map.of("tasks.types", all, "example.orders", all), users.unit("import"));
        session = TestSession.signIn(wac, importer.login());
        files = new ImportFiles(wac);
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    @DisplayName("10.1: the template names the columns, hides their keys and lists the values of each choice")
    void templateHasTitlesKeysAndHints() throws Exception {
        MockHttpServletResponse template = session.send(
                get("/api/v1/entities/" + ORDERS + "/import-template").param("lang", "en"));
        assertThat(template.getStatus()).isEqualTo(200);
        assertThat(template.getHeader("Content-Disposition")).contains("example_orders_import.xlsx");
        List<List<String>> rows = ImportFiles.rows(template.getContentAsByteArray());
        assertThat(rows.get(1)).contains("number", "orderDate", "customer", "currency", "orgUnitId", "comment");
        assertThat(rows.get(1)).as("read-only and computed fields").doesNotContain("status", "total", "lines");
        assertThat(rows.get(0)).contains("Customer *");
        try (var book = new org.dhatim.fastexcel.reader.ReadableWorkbook(
                new java.io.ByteArrayInputStream(template.getContentAsByteArray()))) {
            assertThat(book.getSheets().count())
                    .as("the data sheet and the currency hint")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("10.1: a dry run runs every check and leaves no record, audit row or event")
    void dryRunLeavesNothing() throws Exception {
        long audit = count("select count(*) from audit_log where table_name = 'ms_task_types'");
        long outbox = count("select count(*) from kwh_outbox");
        Map<String, Object> journal = files.run(
                session,
                importer.id(),
                TYPES,
                ImportFiles.workbook(
                        List.of("code", "name", "color"),
                        List.of(
                                Map.of("code", "imp_" + tag, "name", "Imported " + tag),
                                Map.of("code", "bad_" + tag, "name", "Bad", "color", "red"))),
                "dry_run");
        assertThat(journal)
                .containsEntry("state", "done")
                .containsEntry("mode", "dry_run")
                .containsEntry("rowsTotal", 2)
                .containsEntry("rowsDone", 2)
                .containsEntry("created", 1)
                .containsEntry("failed", 1);
        assertThat(ImportFiles.errorFields(journal)).containsExactly("rows[4].color");
        assertThat(count("select count(*) from ms_task_types where code in ('imp_" + tag + "', 'bad_" + tag + "')"))
                .isZero();
        assertThat(count("select count(*) from audit_log where table_name = 'ms_task_types'"))
                .isEqualTo(audit);
        assertThat(count("select count(*) from kwh_outbox")).isEqualTo(outbox);
    }

    @Test
    @DisplayName("10.1: apply creates through the hooks, a row with a known key changes the record, audited as import")
    void applyUpsertsThroughTheHooks() throws Exception {
        String code = "imp_" + tag;
        byte[] create = ImportFiles.workbook(List.of("code", "name"), List.of(Map.of("code", code, "name", "First")));
        assertThat(files.run(session, importer.id(), TYPES, create, "apply")).containsEntry("created", 1);
        Long order = jdbc.sql("select sort_order from ms_task_types where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
        assertThat(order).as("the place the create hook gives").isNotNull();

        byte[] change = ImportFiles.workbook(List.of("code", "name"), List.of(Map.of("code", code, "name", "Second")));
        Map<String, Object> journal = files.run(session, importer.id(), TYPES, change, "apply");
        assertThat(journal).containsEntry("updated", 1).containsEntry("created", 0);
        assertThat(jdbc.sql("select name from ms_task_types where code = :code")
                        .param("code", code)
                        .query(String.class)
                        .single())
                .isEqualTo("Second");
        assertThat(jdbc.sql("select new_row->>'_action' from audit_log where table_name = 'ms_task_types'"
                                + " and new_row->>'code' = :code order by id")
                        .param("code", code)
                        .query(String.class)
                        .list())
                .containsExactly("import", "import");
    }

    @Test
    @DisplayName("10.1: a key the server gives finds a record; a row cannot set it, a row without it gets one")
    void aServerKeyIsReadOnly() throws Exception {
        byte[] file = ImportFiles.workbook(
                List.of("number", "customer"),
                List.of(Map.of("customer", "Customer " + tag), Map.of("number", "X-" + tag, "customer", "Other")));
        Map<String, Object> journal = files.run(session, importer.id(), ORDERS, file, "apply");
        assertThat(journal).containsEntry("created", 1).containsEntry("failed", 1);
        assertThat(ImportFiles.errorFields(journal)).containsExactly("rows[4].number");
        String number = jdbc.sql("select number from ex_orders where customer = :customer")
                .param("customer", "Customer " + tag)
                .query(String.class)
                .single();
        assertThat(number).startsWith("ORD-");

        byte[] change = ImportFiles.workbook(
                List.of("number", "comment"), List.of(Map.of("number", number, "comment", "changed " + tag)));
        assertThat(files.run(session, importer.id(), ORDERS, change, "apply")).containsEntry("updated", 1);
    }

    @Test
    @DisplayName("10.1: a column nobody may fill, an empty file: the import fails with the file's problem")
    void problemsOfTheWholeFile() throws Exception {
        Map<String, Object> unknown = files.run(
                session,
                importer.id(),
                TYPES,
                ImportFiles.workbook(List.of("code", "system"), List.of(Map.of("code", "x_" + tag))),
                "dry_run");
        assertThat(unknown).containsEntry("state", "failed").containsEntry("errorCode", "IMPORT_STRUCTURE");
        assertThat(ImportFiles.errorFields(unknown)).containsExactly("columns[1]");

        Map<String, Object> empty =
                files.run(session, importer.id(), TYPES, ImportFiles.workbook(List.of("code"), List.of()), "dry_run");
        assertThat(empty).containsEntry("state", "failed").containsEntry("errorCode", "IMPORT_EMPTY");
    }

    @Test
    @DisplayName("10.1: the request is checked at once; only the owner reads the journal row and its report")
    void requestAndJournalChecks() throws Exception {
        byte[] file = ImportFiles.workbook(List.of("code"), List.of(Map.of("code", "y_" + tag)));
        UUID own = files.upload(importer.id(), file);
        MockHttpServletResponse mode = ImportFiles.start(session, TYPES, own, "maybe");
        assertThat(mode.getStatus()).isEqualTo(422);
        assertThat(mode.getContentAsString(StandardCharsets.UTF_8)).contains("\"mode\"");

        TestUsers users = TestUsers.of(wac);
        TestUser other = users.withRights(
                Map.of("tasks.types", Set.of("view", "create", "update", "import")), users.unit("other"));
        TestSession otherSession = TestSession.signIn(wac, other.login());
        MockHttpServletResponse foreign = ImportFiles.start(otherSession, TYPES, own, "dry_run");
        assertThat(foreign.getStatus()).as("somebody else's file").isEqualTo(422);
        assertThat(foreign.getContentAsString(StandardCharsets.UTF_8)).contains("fileId");

        MockHttpServletResponse started = ImportFiles.start(session, TYPES, own, "dry_run");
        assertThat(started.getStatus()).isEqualTo(202);
        String id = String.valueOf(TestSession.object(started).get("id"));
        assertThat(otherSession.send(get("/api/v1/imports/" + id)).getStatus()).isEqualTo(404);
        assertThat(session.send(get("/api/v1/imports/" + id + "/report")).getStatus())
                .as("the report before the job ran")
                .isEqualTo(409);
        assertThat(session.send(get("/api/v1/imports/not-a-uuid")).getStatus()).isEqualTo(404);

        ImportFiles.start(session, TYPES, own, "dry_run");
        ImportFiles.start(session, TYPES, own, "dry_run");
        MockHttpServletResponse busy = ImportFiles.start(session, TYPES, own, "dry_run");
        assertThat(busy.getStatus()).as("a fourth import while three wait").isEqualTo(409);
        jdbc.sql("delete from fnd_job_queue").update();
        jdbc.sql("update report_imports set state = 'failed' where user_id = :user")
                .param("user", importer.id())
                .update();
    }

    @Test
    @DisplayName("10.1: a person blocked before the job runs: the import fails as forbidden and writes nothing")
    void blockedOwnerFailsTheImport() throws Exception {
        byte[] file = ImportFiles.workbook(List.of("code"), List.of(Map.of("code", "w_" + tag)));
        MockHttpServletResponse started = ImportFiles.start(session, TYPES, files.upload(importer.id(), file), "apply");
        assertThat(started.getStatus()).isEqualTo(202);
        String id = String.valueOf(TestSession.object(started).get("id"));
        jdbc.sql("update md_users set state = 'P' where id = :id")
                .param("id", importer.id())
                .update();
        wac.getBean(com.smartup24.cms.instance.jobs.runner.JobRunner.class).runQueued();
        assertThat(jdbc.sql("select state || ':' || error_code from report_imports where public_id = :id")
                        .param("id", UUID.fromString(id))
                        .query(String.class)
                        .single())
                .isEqualTo("failed:IMPORT_FORBIDDEN");
        assertThat(count("select count(*) from ms_task_types where code = 'w_" + tag + "'"))
                .isZero();
    }

    @Test
    @DisplayName("10.1: an import whose week is over goes with its report and its problems")
    void cleanupRemovesExpiredImports() throws Exception {
        Map<String, Object> journal = files.run(
                session,
                importer.id(),
                TYPES,
                ImportFiles.workbook(List.of("code", "color"), List.of(Map.of("code", "z_" + tag, "color", "x"))),
                "dry_run");
        assertThat(journal).containsEntry("report", true);
        jdbc.sql("update report_imports set expires_at = now() - interval '1 day' where public_id = :id")
                .param("id", UUID.fromString(String.valueOf(journal.get("id"))))
                .update();
        assertThat(imports.cleanup()).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from report_imports where public_id = '" + journal.get("id") + "'"))
                .isZero();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
