package com.smartup24.cms.instance.support.entity;

import static com.smartup24.cms.instance.support.entity.KitWorld.fieldErrors;
import static com.smartup24.cms.instance.support.entity.KitWorld.ifMatch;
import static com.smartup24.cms.instance.support.entity.KitWorld.number;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.entity.EntitySamples.Sample;
import com.smartup24.cms.instance.support.entity.KitWorld.Created;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The rows of a document (ADR-0032, 9.1 and 11.2, "collections and process"): a record reads back its rows in their
 * order; a row's mistake is addressed {@code lines[i].field} by its place in the body; a row of another record, an
 * unknown property of a row and more rows than the collection takes are refused; a change of the rows keeps the rows it
 * names, inserts the new and deletes the left out ones in one save that raises the record's revision, and the history
 * names the change.
 */
final class KitCollectionChecks {

    private final KitWorld world;

    KitCollectionChecks(KitWorld world) {
        this.world = world;
    }

    List<DynamicTest> collections() {
        List<DynamicTest> tests = new ArrayList<>();
        for (EntityCollection collection : world.model.collections()) {
            String key = collection.key();
            tests.add(dynamicTest(key + ": a record reads back its rows in their order", () -> readBack(collection)));
            for (EntityField field : collection.written()) {
                FormField form = Objects.requireNonNull(field.formField());
                if (form.required()) {
                    tests.add(dynamicTest(
                            key + "[last]." + field.key() + " left out is 422 required on the row",
                            () -> refusedRow(collection, field.key(), null, true, "required")));
                }
                for (Sample sample : EntitySamples.invalid(form)) {
                    if (field.type() == FieldType.MONEY) continue;
                    tests.add(dynamicTest(
                            key + "[last]." + field.key() + " = " + sample.value() + " is 422 " + sample.code(),
                            () -> refusedRow(collection, field.key(), sample.value(), false, sample.code())));
                }
            }
            tests.add(dynamicTest(
                    key + ": an unknown property of a row is 422 unknown_field on the row",
                    () -> refusedRow(collection, "kitUnknown", "x", false, EntityFieldRights.UNKNOWN_FIELD)));
            tests.add(dynamicTest(key + ": a row of another record is 422 not_found", () -> foreignRow(collection)));
            tests.add(dynamicTest(
                    key + ": more rows than " + collection.maxRows() + " is 422 too_many", () -> tooMany(collection)));
            tests.add(dynamicTest(
                    key + ": a change of the rows keeps, inserts and deletes rows and raises the revision once",
                    () -> replaced(collection)));
        }
        return tests;
    }

    /** The rows every record the kit creates has: the fixture's, or one made up from the fields of a row. */
    List<Map<String, Object>> rows(EntityCollection collection) {
        Object given = world.fixture.validValues().get(collection.key());
        if (given instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> row = new LinkedHashMap<>();
                ((Map<?, ?>) item).forEach((name, value) -> row.put(String.valueOf(name), value));
                rows.add(row);
            }
            return rows;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        String token = world.token();
        for (EntityField field : collection.written()) {
            FormField form = Objects.requireNonNull(field.formField());
            Optional<Object> value =
                    field.options().currencyFrom() != null ? Optional.of("10.00") : EntitySamples.valid(form, token);
            value.ifPresent(found -> row.put(field.key(), found));
        }
        return new ArrayList<>(List.of(row));
    }

    /** The values of a record to create with these rows of the collection. */
    Map<String, Object> withRows(EntityCollection collection, List<Map<String, Object>> rows) {
        Map<String, Object> values = world.validValues(world.owner);
        values.put(collection.key(), rows);
        return values;
    }

    private void readBack(EntityCollection collection) throws Exception {
        List<Map<String, Object>> sent = rows(collection);
        sent.add(new LinkedHashMap<>(sent.getFirst()));
        Created record = created(withRows(collection, sent));
        List<Map<String, Object>> read = readRows(record.id(), collection);
        assertThat(read).hasSize(sent.size());
        for (int index = 0; index < read.size(); index++) {
            assertThat(read.get(index)).containsEntry(EntityCollection.POSITION, index + 1);
            assertThat(read.get(index).get(EntityCollection.ID)).isInstanceOf(Number.class);
        }
    }

    private void refusedRow(EntityCollection collection, String field, Object value, boolean leaveOut, String code)
            throws Exception {
        List<Map<String, Object>> sent = rows(collection);
        Map<String, Object> second = new LinkedHashMap<>(sent.getFirst());
        if (leaveOut) {
            second.remove(field);
        } else {
            second.put(field, value);
        }
        sent.add(second);
        MockHttpServletResponse refused = world.post(world.owner, withRows(collection, sent));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        int last = sent.size() - 1;
        assertThat(fieldErrors(refused)).containsExactly(collection.key() + "[" + last + "]." + field + ":" + code);
    }

    private void foreignRow(EntityCollection collection) throws Exception {
        Created other = created(withRows(collection, rows(collection)));
        long foreignRow = number(readRows(other.id(), collection).getFirst().get(EntityCollection.ID));
        Created record = created(withRows(collection, rows(collection)));
        Map<String, Object> row = new LinkedHashMap<>(rows(collection).getFirst());
        row.put(EntityCollection.ID, foreignRow);
        MockHttpServletResponse refused = world.update(
                world.owner, record.id(), Map.of(collection.key(), List.of(row)), ifMatch(record.revision()));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(fieldErrors(refused)).contains(collection.key() + "[0].id:not_found");
        assertThat(readRows(other.id(), collection))
                .as("the other record's rows")
                .hasSize(rows(collection).size());
    }

    private void tooMany(EntityCollection collection) throws Exception {
        List<Map<String, Object>> sent = new ArrayList<>();
        Map<String, Object> row = rows(collection).getFirst();
        for (int index = 0; index <= collection.maxRows(); index++) {
            sent.add(row);
        }
        MockHttpServletResponse refused =
                world.session(world.owner).send(post(world.transport.collection()), withRows(collection, sent), null);
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(fieldErrors(refused)).contains(collection.key() + ":too_many");
    }

    private void replaced(EntityCollection collection) throws Exception {
        List<Map<String, Object>> sent = rows(collection);
        sent.add(new LinkedHashMap<>(sent.getFirst()));
        Created record = created(withRows(collection, sent));
        List<Map<String, Object>> before = readRows(record.id(), collection);
        long kept = number(before.getFirst().get(EntityCollection.ID));
        long dropped = number(before.get(1).get(EntityCollection.ID));
        Map<String, Object> keep = new LinkedHashMap<>(rows(collection).getFirst());
        keep.put(EntityCollection.ID, kept);
        Map<String, Object> fresh = new LinkedHashMap<>(rows(collection).getFirst());
        MockHttpServletResponse changed = world.update(
                world.owner, record.id(), Map.of(collection.key(), List.of(fresh, keep)), ifMatch(record.revision()));
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(number(TestSession.object(changed).get("revision")))
                .as("the record's revision rises once")
                .isEqualTo(record.revision() + 1);
        List<Map<String, Object>> after = readRows(record.id(), collection);
        assertThat(after).hasSize(2);
        assertThat(number(after.get(1).get(EntityCollection.ID)))
                .as("the kept row moved second")
                .isEqualTo(kept);
        assertThat(after.stream().map(row -> number(row.get(EntityCollection.ID))))
                .as("the left out row is deleted")
                .doesNotContain(dropped);
        if (world.has(EntityCapability.HISTORY)) {
            assertThat(world.history(world.owner, record.id()).getContentAsString())
                    .as("the history names the change of the rows")
                    .contains("\"field\":\"" + collection.key() + "\"");
        }
    }

    private Created created(Map<String, Object> values) throws Exception {
        MockHttpServletResponse response = world.post(world.owner, values);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        Map<String, Object> body = TestSession.object(response);
        return new Created(number(body.get("id")), values, number(body.get("revision")), response);
    }

    private List<Map<String, Object>> readRows(long id, EntityCollection collection) throws Exception {
        Object rows = world.readOk(world.owner, id).get(collection.key());
        assertThat(rows).as("the rows of " + collection.key()).isInstanceOf(List.class);
        List<Map<String, Object>> read = new ArrayList<>();
        for (Object row : (List<?>) rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<?, ?>) row).forEach((name, value) -> copy.put(String.valueOf(name), value));
            read.add(copy);
        }
        return read;
    }
}
