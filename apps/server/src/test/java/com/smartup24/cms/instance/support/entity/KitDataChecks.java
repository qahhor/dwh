package com.smartup24.cms.instance.support.entity;

import static com.smartup24.cms.instance.support.entity.KitWorld.fieldErrors;
import static com.smartup24.cms.instance.support.entity.KitWorld.ifMatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.entity.EntitySamples.Sample;
import com.smartup24.cms.instance.support.entity.KitWorld.Created;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * What the entity's declaration promises about its data (ADR-0032, 11.2, "metadata", "validation", "audit"): the
 * screen's metadata is the declaration, every rule of every written field refuses its invalid values with 422 on the
 * field, and every declared field reaches the history with its label.
 */
final class KitDataChecks {

    /** A body larger than this goes without an idempotency key, which keeps only smaller bodies for a replay. */
    private static final int IDEMPOTENT_BODY = 60 * 1024;

    private final KitWorld world;

    KitDataChecks(KitWorld world) {
        this.world = world;
    }

    List<DynamicTest> metadata() {
        return List.of(dynamicTest(
                "form-meta and query-meta give every declared field with its type", this::metadataFollowsTheFields));
    }

    List<DynamicTest> validation() {
        List<DynamicTest> tests = new ArrayList<>();
        for (EntityField field : world.writable()) {
            if (world.form(field).required()) {
                tests.add(dynamicTest(field.key() + " left out of a create is 422 required", () -> {
                    Map<String, Object> values = world.validValues(world.owner);
                    values.remove(field.key());
                    expectProblem(values, new Sample(field.key(), null, "required", true));
                }));
            }
            for (Sample sample : EntitySamples.invalid(world.form(field))) {
                tests.add(dynamicTest(describe(sample), () -> invalid(sample)));
            }
        }
        for (EntityFixture.Invalid declared : world.fixture.invalidValues()) {
            Sample sample = new Sample(declared.field(), declared.value(), declared.code(), true);
            tests.add(dynamicTest(describe(sample), () -> invalid(sample)));
        }
        if (world.transport.strictBody()) {
            tests.add(dynamicTest("an unknown property is 422 unknown_field", () -> {
                Map<String, Object> values = world.validValues(world.owner);
                values.put("kitUnknown", "x");
                expectProblem(values, new Sample("kitUnknown", "x", EntityFieldRights.UNKNOWN_FIELD, true));
            }));
        }
        return tests;
    }

    List<DynamicTest> audit() {
        if (!world.has(EntityCapability.HISTORY)) return List.of();
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest(
                "a create writes every declared field to the history, each with its label", this::createIsAudited));
        tests.add(dynamicTest("an update writes the changed fields to the history", this::updateIsAudited));
        if (world.declares(EntityDefinition.DELETE)) {
            tests.add(dynamicTest("a delete keeps every declared field in the audit log", this::deleteIsAudited));
        }
        return tests;
    }

    private void metadataFollowsTheFields() throws Exception {
        TestSession session = world.session(world.owner);
        MockHttpServletResponse formMeta = session.send(get("/api/v1/form-meta/" + world.entity.code()));
        assertThat(formMeta.getStatus()).as(formMeta.getContentAsString()).isEqualTo(200);
        Map<String, String> formTypes = types(TestSession.object(formMeta));
        Map<String, String> listTypes = Map.of();
        if (world.entity.listCode() != null) {
            MockHttpServletResponse queryMeta = session.send(get("/api/v1/query-meta/" + world.entity.listCode()));
            assertThat(queryMeta.getStatus()).as(queryMeta.getContentAsString()).isEqualTo(200);
            listTypes = types(TestSession.object(queryMeta));
        }
        for (EntityField field : world.model.fields()) {
            if (field.form() != null) {
                assertThat(formTypes)
                        .as("form-meta")
                        .containsEntry(field.key(), field.type().wire());
            }
            if (field.list() != null) {
                assertThat(listTypes)
                        .as("query-meta")
                        .containsEntry(field.key(), field.type().listType().wire());
            }
        }
    }

    private void invalid(Sample sample) throws Exception {
        Map<String, Object> values = world.validValues(world.owner);
        values.put(sample.field(), sample.value());
        expectProblem(values, sample);
    }

    /** A create with these values is refused: 422 on the field, or for a mistyped value a 400 of a typed request. */
    private void expectProblem(Map<String, Object> values, Sample sample) throws Exception {
        boolean large = TestSession.JSON.writeValueAsString(values).length() > IDEMPOTENT_BODY;
        MockHttpServletResponse refused = large
                ? world.session(world.owner).send(post(world.transport.collection()), values, null)
                : world.post(world.owner, values);
        if (sample.typed() || world.transport.strictBody()) {
            assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
            assertThat(fieldErrors(refused)).contains(sample.field() + ":" + sample.code());
        } else {
            assertThat(refused.getStatus()).as(refused.getContentAsString()).isIn(400, 422);
        }
    }

    private void createIsAudited() throws Exception {
        Created record = world.create(world.owner);
        Map<String, Object> created = entry(record.id(), "I");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) created.get("changes");
        Set<String> fields = changes.stream()
                .map(change -> String.valueOf(change.get("field")))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(fields).as("fields of the create in the history").containsAll(audited(record.values()));
        assertThat(changes)
                .as("every field of the history is labelled")
                .allSatisfy(
                        change -> assertThat(Objects.requireNonNullElse(change.get("labelKey"), change.get("label")))
                                .as("label of %s", change.get("field"))
                                .isNotNull());
    }

    private void updateIsAudited() throws Exception {
        Created record = world.create(world.owner);
        Map<String, Object> change = world.updateValues();
        MockHttpServletResponse updated = world.update(world.owner, record.id(), change, ifMatch(record.revision()));
        assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes =
                (List<Map<String, Object>>) entry(record.id(), "U").get("changes");
        assertThat(changes.stream().map(item -> String.valueOf(item.get("field"))))
                .as("fields of the update in the history")
                .containsAll(audited(change));
    }

    private void deleteIsAudited() throws Exception {
        Created record = world.create(world.owner);
        assertThat(world.delete(world.owner, record.id(), null).getStatus()).isEqualTo(204);
        String oldRow = world.jdbc
                .sql("""
                        select old_row::text from audit_log
                        where table_name = :table and row_pk = :id and event = 'D'
                        """)
                .param("table", Objects.requireNonNull(world.entity.auditTable()))
                .param("id", String.valueOf(record.id()))
                .query(String.class)
                .single();
        Map<String, Object> row = TestSession.JSON.readValue(oldRow, Map.class);
        assertThat(row.keySet())
                .as("fields of the deleted record in the audit log")
                .containsAll(audited(record.values()));
    }

    /** The newest history entry of the record with this event. */
    private Map<String, Object> entry(long id, String event) throws Exception {
        MockHttpServletResponse history = world.history(world.owner, id);
        assertThat(history.getStatus()).as(history.getContentAsString()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) TestSession.object(history).get("items");
        return items.stream()
                .filter(item -> event.equals(item.get("event")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + event + " entry in the history of " + id + ": " + items));
    }

    /** The written fields the history keeps: declared with history, with a value that is not empty. */
    private Set<String> audited(Map<String, Object> written) {
        Set<String> keys = new TreeSet<>();
        for (EntityField field : world.writable()) {
            Object value = written.get(field.key());
            if (field.history() && value != null && !"".equals(value)) keys.add(field.key());
        }
        return keys;
    }

    private static Map<String, String> types(Map<String, Object> meta) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Object field : (List<?>) meta.get("fields")) {
            Map<?, ?> item = (Map<?, ?>) field;
            types.put(String.valueOf(item.get("key")), String.valueOf(item.get("type")));
        }
        return types;
    }

    private static String describe(Sample sample) {
        String value = String.valueOf(sample.value());
        String shown = value.length() > 24 ? value.substring(0, 12) + "…(" + value.length() + ")" : value;
        return sample.field() + " = " + shown + " is refused (" + sample.code() + ")";
    }
}
