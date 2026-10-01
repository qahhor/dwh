package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.FormFieldMetas.ConditionClauseMeta;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.ConditionItemMeta;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.DefaultValueMeta;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormFieldMeta;
import com.smartup24.cms.instance.common.entity.field.FieldCondition;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.2 (ADR-0032, 4.1–4.5): {@code form-meta} gives each field its flags and type parameters only when
 * it has them — an enumeration its items with their names, read-only kinds, a computed value, defaults, conditions,
 * scale, item count, file types, currencies and the JSON root.
 */
class FormFieldMetasTest {

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void everyFlagAndParameterIsGivenWhenTheFieldHasIt() {
        Map<String, FormFieldMeta> fields = FormMetaController.of(
                        FieldTypesFixture.DEFINITION,
                        code -> code.equals(FieldTypesFixture.UNITS) ? Map.of("kg", "Kilogram") : Map.of())
                .fields()
                .stream()
                .collect(Collectors.toMap(FormFieldMeta::key, Function.identity()));

        assertThat(fields.get("unit").type()).isEqualTo("enum");
        assertThat(fields.get("unit").options()).containsExactly("kg");
        assertThat(fields.get("unit").optionLabels()).containsEntry("kg", "Kilogram");
        assertThat(fields.get("ownerId").visibleWhen())
                .containsExactly(new ConditionItemMeta("kind", "eq", List.of("wholesale"), null));
        assertThat(fields.get("number").readonly()).isEqualTo("always");
        assertThat(fields.get("number").defaultValue()).isEqualTo(new DefaultValueMeta("sequence", "T-{0000}"));
        assertThat(fields.get("code").readonly()).isEqualTo("on_update");
        assertThat(fields.get("doubled").computed()).isTrue();
        assertThat(fields.get("doubled").readonly()).isEqualTo("always");
        assertThat(fields.get("due").defaultValue()).isEqualTo(new DefaultValueMeta("today", null));
        assertThat(fields.get("total").currencies()).containsExactly("UZS", "USD");
        assertThat(fields.get("photo").contentTypes()).containsExactly("image/png", "image/jpeg", "image/webp");
        assertThat(fields.get("attachment").maxBytes()).isEqualTo(1_000_000L);
        assertThat(fields.get("extra").jsonRoot()).isEqualTo("object");
        assertThat(fields.get("tagIds").maxItems()).isEqualTo(3);
        assertThat(fields.get("qty").scale()).isEqualTo(2);

        FormFieldMeta title = fields.get("title");
        assertThat(List.of(
                        title.optionLabels() == null,
                        title.readonly() == null,
                        title.computed() == null,
                        title.defaultValue() == null,
                        title.visibleWhen() == null,
                        title.currencies() == null,
                        title.contentTypes() == null,
                        title.jsonRoot() == null))
                .as("a field without flags answers as before item 5.2")
                .containsOnly(true);
    }

    @Test
    void aGroupOfSeveralClausesIsAnyOfThem() {
        FieldCondition condition = FieldCondition.eq("kind", "a")
                .and(FieldCondition.any(
                        new FieldCondition.Clause("vip", FieldCondition.Op.EQ, List.of("true")),
                        new FieldCondition.Clause("ownerId", FieldCondition.Op.NOT_EMPTY, List.of())));

        assertThat(FormFieldMetas.condition(condition))
                .containsExactly(
                        new ConditionItemMeta("kind", "eq", List.of("a"), null),
                        new ConditionItemMeta(
                                null,
                                null,
                                null,
                                List.of(
                                        new ConditionClauseMeta("vip", "eq", List.of("true")),
                                        new ConditionClauseMeta("ownerId", "not_empty", List.of()))));
        assertThat(FormFieldMetas.condition(null)).isNull();
    }
}
