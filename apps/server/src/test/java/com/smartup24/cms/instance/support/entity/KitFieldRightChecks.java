package com.smartup24.cms.instance.support.entity;

import static com.smartup24.cms.instance.support.entity.KitWorld.fieldErrors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import com.smartup24.cms.instance.support.entity.KitWorld.Created;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The rights on fields (ADR-0032, 5.2 and 11.2, "field rights"): a field whose right the viewer lacks does not exist
 * for them — not in {@code form-meta}, {@code query-meta}, the records they read, their list or their export, and a
 * value sent for it is refused as an unknown field; a field they may not write is read-only there and a value for it is
 * refused with {@code readonly}. A holder of the right sees and writes both.
 */
final class KitFieldRightChecks {

    private final KitWorld world;
    private final KitExportChecks exports;

    KitFieldRightChecks(KitWorld world) {
        this.world = world;
        this.exports = new KitExportChecks(world);
    }

    List<DynamicTest> fieldRights() {
        List<DynamicTest> tests = new ArrayList<>();
        for (EntityField field : world.model.fields()) {
            String key = field.key();
            if (field.access().restricted()) {
                tests.add(dynamicTest(
                        key + ": absent from form-meta and query-meta without its right", () -> hiddenInMeta(field)));
                tests.add(dynamicTest(
                        key + ": absent from the records and the list read without its right",
                        () -> hiddenInRecords(field)));
                if (EntitySamples.writable(field)) {
                    tests.add(dynamicTest(
                            key + ": a value for it without its right is 422 unknown_field",
                            () -> refused(field, EntityFieldRights.UNKNOWN_FIELD)));
                }
                if (field.list() != null && world.has(EntityCapability.EXPORT)) {
                    tests.add(dynamicTest(
                            key + ": the export refuses its column without its right",
                            () -> assertThat(exports.request(plain(), List.of(key))
                                            .getStatus())
                                    .isEqualTo(422)));
                }
            }
            if (field.access().guardsWriting() && EntitySamples.writable(field)) {
                tests.add(dynamicTest(
                        key + ": read-only in form-meta without its write right", () -> readonlyInMeta(field)));
                tests.add(dynamicTest(
                        key + ": a value for it without its write right is 422 readonly",
                        () -> refused(field, EntityValidator.READONLY)));
            }
        }
        return tests;
    }

    private TestUser plain() {
        return Objects.requireNonNull(world.plain, "a user without the field rights");
    }

    private void hiddenInMeta(EntityField field) throws Exception {
        assertThat(formMetaField(plain(), field))
                .as("form-meta without the right")
                .isEmpty();
        if (field.form() != null) {
            assertThat(formMetaField(world.owner, field))
                    .as("form-meta with the right")
                    .isPresent();
        }
        if (field.list() != null) {
            assertThat(listKeys(plain())).as("query-meta without the right").doesNotContain(field.key());
            assertThat(listKeys(world.owner)).as("query-meta with the right").contains(field.key());
        }
    }

    private void hiddenInRecords(EntityField field) throws Exception {
        Created own = world.create(plain());
        assertThat(world.readOk(plain(), own.id())).as("the record read").doesNotContainKey(field.key());
        assertThat(world.items(plain()))
                .as("the list read")
                .allSatisfy(item -> assertThat(item).doesNotContainKey(field.key()));
        Created held = world.create(world.owner);
        assertThat(world.readOk(world.owner, held.id()))
                .as("the record a holder reads")
                .containsKey(field.key());
    }

    private void refused(EntityField field, String code) throws Exception {
        Map<String, Object> values = world.validValues(plain());
        Object value = EntitySamples.valid(world.form(field), world.token())
                .or(() -> Optional.ofNullable(world.fixture.validValues().get(field.key())))
                .orElseThrow(() -> new AssertionError("a value of " + field.key() + " in EntityFixture.valid(...)"));
        values.put(field.key(), value);
        MockHttpServletResponse response = world.post(plain(), values);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        assertThat(fieldErrors(response)).contains(field.key() + ":" + code);
        MockHttpServletResponse held = world.post(world.owner, values);
        assertThat(held.getStatus())
                .as("a holder writes it: %s", held.getContentAsString())
                .isEqualTo(201);
    }

    private void readonlyInMeta(EntityField field) throws Exception {
        assertThat(formMetaField(plain(), field))
                .as("form-meta without the write right")
                .hasValueSatisfying(meta -> assertThat(meta).containsEntry("readonly", true));
        assertThat(formMetaField(world.owner, field))
                .as("form-meta with the write right")
                .hasValueSatisfying(meta -> assertThat(meta).containsEntry("readonly", false));
    }

    private Optional<Map<String, Object>> formMetaField(TestUser who, EntityField field) throws Exception {
        MockHttpServletResponse meta = world.session(who).send(get("/api/v1/form-meta/" + world.entity.code()));
        assertThat(meta.getStatus()).as(meta.getContentAsString()).isEqualTo(200);
        return ((List<?>) TestSession.object(meta).get("fields"))
                .stream()
                        .map(KitFieldRightChecks::asObject)
                        .filter(item -> field.key().equals(item.get("key")))
                        .findFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object item) {
        return (Map<String, Object>) item;
    }

    private List<String> listKeys(TestUser who) throws Exception {
        return exports.columns(who);
    }
}
