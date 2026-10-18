package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.FieldParams;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.FormFlags;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A file field that holds several files (ADR-0043): its value is an array of distinct file ids up to its limit, the
 * same files in the same order are the same value, an empty array is no file, and a single file field never takes an
 * array.
 */
class EntityFileValuesTest {

    private static final UUID A = UUID.fromString("6f1c2a52-6b0e-4d3e-9a51-1f2d3c4b5a69");
    private static final UUID B = UUID.fromString("0b7c3a1e-2f4d-4c5b-8a69-1e2d3c4b5a60");
    private static final FormField SEVERAL = field(2);
    private static final FormField SINGLE = field(null);

    @Test
    void severalFilesAreDistinctIdsWithinTheLimit() {
        assertThat(EntityFileValues.several(SEVERAL)).isTrue();
        assertThat(EntityFileValues.several(SINGLE)).isFalse();
        assertThat(problem(SEVERAL, List.of(A.toString(), Map.of("id", B.toString()))))
                .isNull();
        assertThat(problem(SEVERAL, List.of(A, B, UUID.randomUUID()))).isEqualTo(FieldValueRules.TOO_MANY);
        assertThat(problem(SEVERAL, List.of(A.toString(), A.toString()))).isEqualTo(EntityValidator.INVALID);
        assertThat(problem(SEVERAL, List.of("not a file"))).isEqualTo(EntityValidator.INVALID);
        assertThat(problem(SEVERAL, A.toString())).isEqualTo(EntityValidator.INVALID);
        assertThat(problem(SINGLE, A.toString())).isNull();
        assertThat(problem(SINGLE, List.of(A.toString()))).isEqualTo(EntityValidator.INVALID);
        assertThat(problem(SINGLE, "x")).isEqualTo(EntityValidator.INVALID);
    }

    @Test
    void theSameFilesInTheSameOrderAreTheSameValue() {
        assertThat(FieldValueRules.same(
                        SEVERAL,
                        List.of(A.toString(), B.toString()),
                        List.of(Map.of("id", A.toString(), "name", "a"), Map.of("id", B.toString(), "name", "b"))))
                .isTrue();
        assertThat(FieldValueRules.same(SEVERAL, List.of(B.toString(), A.toString()), List.of(A, B)))
                .isFalse();
        assertThat(FieldValueRules.same(SEVERAL, List.of(), null)).isTrue();
        assertThat(FieldValueRules.same(SINGLE, Map.of("id", A.toString()), A.toString()))
                .isTrue();
        assertThat(FieldValueRules.same(SEVERAL, List.of("x"), List.of("x"))).isFalse();
        assertThat(EntityFileValues.empty(List.of())).isTrue();
        assertThat(EntityFileValues.empty(" ")).isTrue();
        assertThat(EntityFileValues.empty(List.of(A))).isFalse();
        assertThat(EntityFileValues.checked(List.of("x"))).isEmpty();
        assertThat(EntityFileValues.ids(null)).contains(List.of());
    }

    @Test
    void aRequiredFieldOfSeveralFilesRefusesAnEmptyArray() {
        EntityDefinition entity = org.mockito.Mockito.mock(EntityDefinition.class);
        org.mockito.Mockito.when(entity.fields()).thenReturn(List.of(field(3, true)));
        List<FieldErrorItem> problems = EntityValidator.problems(entity, Map.of("docs", List.of()), false);
        assertThat(problems)
                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("docs", EntityValidator.REQUIRED));
    }

    private static String problem(FormField field, Object value) {
        return FieldValueRules.problem(field, value).map(FieldErrorItem::code).orElse(null);
    }

    private static FormField field(Integer maxItems) {
        return field(maxItems, false);
    }

    private static FormField field(Integer maxItems, boolean required) {
        return new FormField(
                "docs",
                "l",
                null,
                FieldType.FILE,
                required,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                FormFlags.NONE,
                new FieldParams(null, maxItems, null, List.of(), List.of(), null, null));
    }
}
