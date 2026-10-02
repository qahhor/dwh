package com.smartup24.cms.instance.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartup24.cms.instance.example.service.ExampleOrderEntity;
import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.instance.support.entity.ImportFiles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * Plan 10/10, item 5.8, acceptance (ADR-0032, 10.1 and 13): an import of 10 000 rows — every row through the steps of
 * a save of the runtime, its audit and its event included — finishes in under 60 s with its error report. The orders
 * of the reference document are imported, one row in a hundred refused, on the embedded PostgreSQL of the build.
 *
 * <p>Tagged {@code import-large}: the everyday suite skips it, the kit's import group proves the behaviour there. Run it
 * on its own, optionally with another number of rows:
 *
 * <pre>
 * mvn -pl apps/server test -Pimport-large [-Dimport.large.rows=20000]
 * </pre>
 */
@Tag("import-large")
class EntityImportPerformanceTest extends EmbeddedPostgresTest {

    private static final Logger log = LoggerFactory.getLogger(EntityImportPerformanceTest.class);
    private static final int ROWS = Integer.getInteger("import.large.rows", 10_000);
    private static final Duration LIMIT = Duration.ofSeconds(60);

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private ModuleRegistryService modules;

    @Test
    @DisplayName("5.8: 10 000 rows are imported in under 60 s, with the report of the refused ones")
    void tenThousandRowsInUnderAMinute() throws Exception {
        modules.toggleModuleStatus("example", true);
        TestUsers users = TestUsers.of(wac);
        TestUser importer = users.withRights(
                Map.of(ExampleOrderEntity.CODE, Set.of("view", "create", "update", "import")),
                users.unit("import-large"));
        TestSession session = TestSession.signIn(wac, importer.login());
        String tag = UUID.randomUUID().toString().substring(0, 8);
        List<Map<String, ?>> rows = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            rows.add(Map.of(
                    "customer",
                    "Customer " + tag + " " + i,
                    "orderDate",
                    "2026-10-01",
                    "currency",
                    i % 100 == 0 ? "XXX" : "UZS",
                    "comment",
                    "Imported row " + i));
        }
        ImportFiles files = new ImportFiles(wac);
        byte[] file = ImportFiles.workbook(List.of("number", "orderDate", "customer", "currency", "comment"), rows);
        UUID fileId = files.upload(importer.id(), file);
        MockHttpServletResponse started = ImportFiles.start(session, ExampleOrderEntity.CODE, fileId, "apply");
        assertThat(started.getStatus()).as(started.getContentAsString()).isEqualTo(202);
        String id = String.valueOf(TestSession.object(started).get("id"));

        long begun = System.nanoTime();
        wac.getBean(JobRunner.class).runQueued();
        Duration took = Duration.ofNanos(System.nanoTime() - begun);
        log.info("import_large rows={} took_ms={}", ROWS, took.toMillis());

        Map<String, Object> journal = TestSession.object(session.send(get("/api/v1/imports/" + id)));
        int refused = (ROWS + 99) / 100;
        assertThat(journal)
                .containsEntry("state", "done")
                .containsEntry("rowsDone", ROWS)
                .containsEntry("created", ROWS - refused)
                .containsEntry("failed", refused)
                .containsEntry("report", true);
        assertThat(session.send(get("/api/v1/imports/" + id + "/report")).getStatus())
                .isEqualTo(200);
        assertThat(took).as("an import of %d rows", ROWS).isLessThan(LIMIT);
    }
}
