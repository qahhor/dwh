package com.smartup24.cms.instance.support.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The export of the entity's list (ADR-0032, 11.2, "export"; ADR-0018): the job writes the rows of the viewer's list —
 * the records of their scope, none of another's — and only the columns the viewer may see; a column they may not see is
 * refused as an unknown one.
 */
final class KitExportChecks {

    private final KitWorld world;

    KitExportChecks(KitWorld world) {
        this.world = world;
    }

    List<DynamicTest> export() {
        if (!world.has(EntityCapability.EXPORT)) return List.of();
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest(
                "the export holds the rows of the viewer's list and every column the viewer asks for",
                () -> rowsFollowTheList(world.owner)));
        if (world.scoped()) {
            tests.add(dynamicTest("the export of a viewer outside the scope holds none of the owner's records", () -> {
                world.create(world.owner);
                world.create(world.outsider);
                rowsFollowTheList(world.outsider);
            }));
        }
        tests.add(dynamicTest(
                "a column that is not in the viewer's list is refused",
                () -> assertThat(
                                request(world.owner, List.of("kitNoSuchColumn")).getStatus())
                        .isEqualTo(422)));
        return tests;
    }

    private void rowsFollowTheList(TestUser who) throws Exception {
        world.create(who);
        List<String> columns = columns(who);
        List<List<String>> rows = rows(who, columns);
        assertThat(rows.getFirst()).as("the header").hasSize(columns.size());
        assertThat(rows.size() - 1)
                .as("the rows of the export, as many as the records of the viewer's list")
                .isEqualTo(world.ids(who, null).size());
    }

    /** The keys of the viewer's list fields, as {@code query-meta} gives them. */
    List<String> columns(TestUser who) throws Exception {
        MockHttpServletResponse meta = world.session(who).send(get("/api/v1/query-meta/" + world.entity.listCode()));
        assertThat(meta.getStatus()).as(meta.getContentAsString()).isEqualTo(200);
        List<String> keys = new ArrayList<>();
        for (Object field : (List<?>) TestSession.object(meta).get("fields")) {
            keys.add(String.valueOf(((Map<?, ?>) field).get("key")));
        }
        return keys;
    }

    /** Asks for an export of the entity's list with these columns. */
    MockHttpServletResponse request(TestUser who, List<String> columns) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("list", world.entity.listCode());
        body.put("lang", "en");
        body.put("columns", columns);
        return world.session(who).send(post("/api/v1/exports"), body);
    }

    /** The rows of a finished export, the header first. */
    List<List<String>> rows(TestUser who, List<String> columns) throws Exception {
        // Jobs other tests left queued would run here too.
        world.jdbc.sql("delete from fnd_job_queue").update();
        MockHttpServletResponse queued = request(who, columns);
        assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
        String id = String.valueOf(TestSession.object(queued).get("id"));
        world.wac.getBean(JobRunner.class).runQueued();
        MockHttpServletResponse file = world.session(who).send(get("/api/v1/exports/" + id + "/file"));
        assertThat(file.getStatus()).as(file.getContentAsString()).isEqualTo(200);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            return workbook.getFirstSheet().read().stream()
                    .map(KitExportChecks::cells)
                    .toList();
        }
    }

    private static List<String> cells(Row row) {
        return row.stream()
                .map(cell -> cell == null ? "" : Objects.requireNonNullElse(cell.getText(), ""))
                .toList();
    }
}
