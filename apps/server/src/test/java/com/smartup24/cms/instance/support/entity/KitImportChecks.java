package com.smartup24.cms.instance.support.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldReadonly;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.instance.support.entity.EntitySamples.Sample;
import com.smartup24.cms.instance.support.entity.KitWorld.Created;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The import of the entity's records (ADR-0032, 10.1 and 11.2): the template holds the key and the fields the importer
 * may write and only them; without the right's {@code import} the import is 403, without {@code view} 404; a dry run
 * checks every row and writes nothing; an applied row creates a record or changes the record its key names in the
 * importer's scope — never one outside it — through the runtime, audited with the import as its source; an invalid row
 * is reported at {@code rows[n].field} while the others are written, and the report lists it; a column the importer
 * may not write refuses the whole file.
 */
final class KitImportChecks {

    private final KitWorld world;
    private final ImportFiles files;

    KitImportChecks(KitWorld world) {
        this.world = world;
        this.files = new ImportFiles(world.wac);
    }

    List<DynamicTest> imports() {
        if (!world.has(EntityCapability.IMPORT)) return List.of();
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest(
                "the template holds the key and every field the importer may write", this::templateFollowsTheRights));
        tests.add(dynamicTest("an import without import is 403, without view 404", this::importNeedsItsRight));
        tests.add(dynamicTest("a dry run checks every row and writes nothing", this::dryRunWritesNothing));
        tests.add(dynamicTest(
                "an applied row creates a record, a row with its key changes it, audited as an import",
                this::applyUpserts));
        tests.add(dynamicTest(
                "an invalid row is reported at rows[n].field, the others are written, the report lists it",
                this::invalidRowsAreReported));
        if (world.scoped()) {
            tests.add(dynamicTest(
                    "a row whose key names a record outside the importer's scope does not change it",
                    this::outsideTheScopeIsNotChanged));
        }
        if (world.plain != null) {
            tests.add(dynamicTest(
                    "a column the importer may not write refuses the whole file", this::restrictedColumnRefused));
        }
        return tests;
    }

    private String key() {
        return Objects.requireNonNull(world.model.importing(), world.entity.code())
                .key();
    }

    private void templateFollowsTheRights() throws Exception {
        List<String> keys = files.templateKeys(world.session(world.owner), world.entity.code());
        assertThat(keys).as("the template's keys").contains(key());
        for (EntityField field : world.writable()) {
            if (field.access().restricted() || field.access().guardsWriting()) continue;
            if (Set.of("file", "image", "json").contains(field.type().wire())) continue;
            if (FieldReadonly.ALWAYS.equals(Objects.requireNonNull(field.form()).readonly())) continue;
            assertThat(keys).as("a field the owner writes").contains(field.key());
        }
        if (world.plain != null) {
            List<String> plain = files.templateKeys(world.session(world.plain), world.entity.code());
            world.model.fields().stream()
                    .filter(field ->
                            field.access().restricted() || field.access().guardsWriting())
                    .forEach(field -> assertThat(plain)
                            .as("a field the importer may not write")
                            .doesNotContain(field.key()));
        }
    }

    private void importNeedsItsRight() throws Exception {
        String template = "/api/v1/entities/" + world.entity.code() + "/import-template";
        assertThat(world.session(world.viewer).send(get(template)).getStatus())
                .as("the template without import")
                .isEqualTo(403);
        assertThat(world.session(world.stranger).send(get(template)).getStatus())
                .as("the template without view")
                .isEqualTo(404);
        byte[] file = ImportFiles.workbook(List.of(key()), List.of(Map.of(key(), world.token())));
        MockHttpServletResponse viewer = ImportFiles.start(
                world.session(world.viewer), world.entity.code(), files.upload(world.viewer.id(), file), "apply");
        assertThat(viewer.getStatus()).as(viewer.getContentAsString()).isEqualTo(403);
        MockHttpServletResponse stranger = ImportFiles.start(
                world.session(world.stranger), world.entity.code(), files.upload(world.stranger.id(), file), "apply");
        assertThat(stranger.getStatus()).as(stranger.getContentAsString()).isEqualTo(404);
    }

    private void dryRunWritesNothing() throws Exception {
        List<String> keys = templateKeys();
        int before = world.ids(world.owner, null).size();
        Map<String, Object> journal =
                run(world.owner, keys, List.of(row(keys, world.validValues(world.owner))), "dry_run");
        assertThat(journal).containsEntry("state", "done").containsEntry("failed", 0);
        assertThat(number(journal, "created") + number(journal, "updated"))
                .as("the row checked")
                .isEqualTo(1);
        assertThat(world.ids(world.owner, null))
                .as("the records after a dry run")
                .hasSize(before);
    }

    private void applyUpserts() throws Exception {
        List<String> keys = templateKeys();
        Set<Long> before = world.ids(world.owner, null);
        Map<String, Object> created =
                run(world.owner, keys, List.of(row(keys, world.validValues(world.owner))), "apply");
        assertThat(created)
                .containsEntry("state", "done")
                .containsEntry("created", 1)
                .containsEntry("failed", 0);
        Set<Long> after = new LinkedHashSet<>(world.ids(world.owner, null));
        after.removeAll(before);
        assertThat(after).as("the imported record in the list").hasSize(1);
        long id = after.iterator().next();
        Object key = Objects.requireNonNull(world.readOk(world.owner, id).get(key()), "the key of the record");
        long revision = world.revision(world.owner, id);

        Map<String, Object> change = new LinkedHashMap<>(world.updateValues());
        change.put(key(), key);
        Map<String, Object> updated = run(world.owner, keys, List.of(row(keys, change)), "apply");
        assertThat(updated)
                .containsEntry("updated", 1)
                .containsEntry("created", 0)
                .containsEntry("failed", 0);
        Map<String, Object> read = world.readOk(world.owner, id);
        assertThat(KitWorld.number(read.get("revision")))
                .as("the revision after the import")
                .isGreaterThan(revision);
        for (Map.Entry<String, Object> sent : change.entrySet()) {
            Optional<EntityField> field = world.model.field(sent.getKey());
            if (field.isEmpty() || !keys.contains(sent.getKey())) continue;
            assertThat(EntitySamples.same(world.form(field.get()), sent.getValue(), KitWorld.value(read, field.get())))
                    .as("%s changed by the import: sent %s, read %s", sent.getKey(), sent.getValue(), read)
                    .isTrue();
        }
        if (world.entity.auditTable() != null) {
            String source = world.jdbc
                    .sql("select new_row->>'_action' from audit_log where table_name = :table"
                            + " and row_pk = :id order by id desc limit 1")
                    .param("table", world.entity.auditTable())
                    .param("id", String.valueOf(id))
                    .query(String.class)
                    .optional()
                    .orElse(null);
            assertThat(source).as("the source of the audited change").isEqualTo("import");
        }
    }

    private void invalidRowsAreReported() throws Exception {
        List<String> keys = templateKeys();
        Optional<Sample> refused = world.writable().stream()
                .filter(field -> keys.contains(field.key()) && !field.key().equals(key()))
                .flatMap(field -> EntitySamples.invalid(world.form(field)).stream())
                .filter(sample -> sample.value() instanceof String text && !text.isBlank())
                .findFirst();
        if (refused.isEmpty()) return;
        Sample sample = refused.get();
        Map<String, Object> bad = new LinkedHashMap<>(world.validValues(world.owner));
        bad.put(sample.field(), sample.value());
        Map<String, Object> journal =
                run(world.owner, keys, List.of(row(keys, world.validValues(world.owner)), row(keys, bad)), "apply");
        assertThat(journal)
                .containsEntry("state", "done")
                .containsEntry("created", 1)
                .containsEntry("failed", 1);
        assertThat(ImportFiles.errorFields(journal)).contains("rows[4]." + sample.field());
        assertThat(journal).containsEntry("report", true);
        MockHttpServletResponse report =
                world.session(world.owner).send(get("/api/v1/imports/" + journal.get("id") + "/report"));
        assertThat(report.getStatus()).as(report.getContentAsString()).isEqualTo(200);
        List<List<String>> rows = ImportFiles.rows(report.getContentAsByteArray());
        assertThat(rows.getFirst()).as("the report's titles").contains("Errors");
    }

    private void outsideTheScopeIsNotChanged() throws Exception {
        Created own = world.create(world.owner);
        Object key = world.readOk(world.owner, own.id()).get(key());
        if (key == null) return;
        List<String> keys = templateKeys();
        Map<String, Object> change = new LinkedHashMap<>(world.updateValues());
        change.put(key(), key);
        Map<String, Object> journal = run(world.outsider, keys, List.of(row(keys, change)), "apply");
        assertThat(journal).containsEntry("updated", 0);
        assertThat(world.revision(world.owner, own.id()))
                .as("the owner's record after an outsider's import")
                .isEqualTo(own.revision());
    }

    private void restrictedColumnRefused() throws Exception {
        TestUser plain = Objects.requireNonNull(world.plain);
        String restricted = world.model.fields().stream()
                .filter(field -> field.access().restricted() || field.access().guardsWriting())
                .map(EntityField::key)
                .findFirst()
                .orElseThrow();
        List<String> keys = List.of(key(), restricted);
        Map<String, Object> journal = run(plain, keys, List.of(Map.of(key(), world.token())), "dry_run");
        assertThat(journal).containsEntry("state", "failed").containsEntry("errorCode", "IMPORT_STRUCTURE");
    }

    private List<String> templateKeys() throws Exception {
        return files.templateKeys(world.session(world.owner), world.entity.code());
    }

    private Map<String, Object> run(TestUser who, List<String> keys, List<Map<String, ?>> rows, String mode)
            throws Exception {
        // Jobs other tests left queued would run here too.
        world.jdbc.sql("delete from fnd_job_queue").update();
        return files.run(world.session(who), who.id(), world.entity.code(), ImportFiles.workbook(keys, rows), mode);
    }

    /** The values of a record that the template has columns for. */
    private static Map<String, ?> row(List<String> keys, Map<String, Object> values) {
        Map<String, Object> row = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (keys.contains(key)) row.put(key, value);
        });
        return row;
    }

    private static int number(Map<String, Object> journal, String key) {
        return ((Number) Objects.requireNonNull(journal.get(key))).intValue();
    }
}
