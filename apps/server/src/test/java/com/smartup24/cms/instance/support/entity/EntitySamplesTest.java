package com.smartup24.cms.instance.support.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.FieldTypesFixture;
import com.smartup24.cms.instance.support.entity.EntitySamples.Sample;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The kit's made-up values (plan 10/10, item 6.2) on the test entity with a field of every type (ADR-0032, 4.8): each
 * invalid value is refused by the server's check with the code the kit expects, each valid value passes it, and every
 * type has its cases — so the validation group of the kit covers every type the field matrix covers.
 */
class EntitySamplesTest {

    private static final EntityDefinition ENTITY = FieldTypesFixture.DEFINITION;

    /** The types whose valid value is a row of another table: only the entity's fixture can give one. */
    private static final Set<FieldType> NEED_A_ROW =
            EnumSet.of(FieldType.REF, FieldType.ENUM, FieldType.MULTI_REF, FieldType.FILE, FieldType.IMAGE);

    @ParameterizedTest(name = "{0}")
    @EnumSource(FieldType.class)
    @DisplayName("6.2: every type has invalid values the server refuses with the expected code")
    void everyInvalidValueIsRefusedWithItsCode(FieldType type) {
        FormField field = field(type);
        List<Sample> samples = EntitySamples.invalid(field);

        assertThat(samples).as("invalid values of " + type).isNotEmpty();
        for (Sample sample : samples) {
            assertThat(problems(field, sample.value()))
                    .as("%s = %s", field.key(), sample.value())
                    .extracting(FieldErrorItem::code)
                    .contains(sample.code());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FieldType.class)
    @DisplayName("6.2: the made-up valid value and the changed one pass the server's check")
    void everyValidValuePasses(FieldType type) {
        FormField field = field(type);
        var valid = EntitySamples.valid(field, "k1a2b3c4d1");
        var changed = EntitySamples.changed(field, "k1a2b3c4d2");

        assertThat(valid.isEmpty())
                .as("only a row of another table has no made-up value")
                .isEqualTo(NEED_A_ROW.contains(type));
        valid.ifPresent(
                value -> assertThat(problems(field, value)).as("valid %s", type).isEmpty());
        changed.ifPresent(value ->
                assertThat(problems(field, value)).as("changed %s", type).isEmpty());
        assertThat(changed.isPresent()).as("a changed value of %s", type).isEqualTo(valid.isPresent());
        if (valid.isPresent() && changed.isPresent()) {
            assertThat(EntitySamples.same(field, valid.get(), changed.get()))
                    .as("the update changes %s", type)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("6.2: a written field and an always read-only one are told apart")
    void writtenFieldsAreTheStoredOnes() {
        Map<String, Boolean> written = new HashMap<>();
        for (EntityField field : Objects.requireNonNull(ENTITY.model()).fields()) {
            written.put(field.key(), EntitySamples.writable(field));
        }

        assertThat(written)
                .containsEntry("title", true)
                .containsEntry("total", true)
                .containsEntry("tagIds", true)
                .containsEntry("code", true)
                .containsEntry("doubled", false)
                .containsEntry("number", false)
                .containsEntry("modifiedAt", false);
    }

    @Test
    @DisplayName("6.2: a moment and a time of day read back in another text form are the same value")
    void sameValuesInAnotherForm() {
        assertThat(EntitySamples.same(field(FieldType.DATETIME), "2026-10-01T09:30:00Z", "2026-10-01T14:30+05:00"))
                .isTrue();
        assertThat(EntitySamples.same(field(FieldType.TIME), "09:30", "09:30:00"))
                .isTrue();
        assertThat(EntitySamples.same(field(FieldType.TIME), "09:30", "09:31")).isFalse();
        assertThat(EntitySamples.same(field(FieldType.EMAIL), "Ann@Example.com", "ann@example.com"))
                .isTrue();
    }

    private static FormField field(FieldType type) {
        return Objects.requireNonNull(ENTITY.fieldsByKey().get(FieldTypesFixture.keyOf(type)));
    }

    /** The problems of the value on its field, in a record that shows every field (ADR-0032, 4.4). */
    private static List<FieldErrorItem> problems(FormField field, Object value) {
        Map<String, Object> record = new HashMap<>();
        record.put("kind", "wholesale");
        record.put(field.key(), value);
        return EntityValidator.problems(ENTITY, record, true).stream()
                .filter(problem -> problem.field().equals(field.key()))
                .toList();
    }
}
