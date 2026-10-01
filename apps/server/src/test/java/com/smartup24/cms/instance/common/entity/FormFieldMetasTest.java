package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.EntityEnums.Items;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.ConditionClauseMeta;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.ConditionItemMeta;
import com.smartup24.cms.instance.common.entity.FormFieldMetas.DefaultValueMeta;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormFieldMeta;
import com.smartup24.cms.instance.common.entity.field.FieldCondition;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                        code -> code.equals(FieldTypesFixture.UNITS)
                                ? new Items(Map.of("kg", "Kilogram", "lb", "Pound"), Set.of("lb"))
                                : Items.NONE)
                .fields()
                .stream()
                .collect(Collectors.toMap(FormFieldMeta::key, Function.identity()));

        assertThat(fields.get("unit").type()).isEqualTo("enum");
        // An archived item is offered no more but is still named, so an old value reads (ADR-0032, 5.4).
        assertThat(fields.get("unit").options()).containsExactly("kg");
        assertThat(fields.get("unit").optionLabels())
                .containsEntry("kg", "Kilogram")
                .containsEntry("lb", "Pound");
        assertThat(fields.get("ownerId").visibleWhen())
                .containsExactly(new ConditionItemMeta("kind", "eq", List.of("wholesale"), null));
        assertThat(fields.get("number").readonly()).isTrue();
        assertThat(fields.get("number").defaultValue()).isEqualTo(new DefaultValueMeta("sequence", "T-{0000}"));
        assertThat(fields.get("code").readonly()).isFalse();
        assertThat(fields.get("code").readonlyOnUpdate()).isTrue();
        assertThat(fields.get("doubled").computed()).isTrue();
        assertThat(fields.get("doubled").readonly()).isTrue();
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
                        !title.readonly(),
                        title.readonlyOnUpdate() == null,
                        title.readonlyWhen() == null,
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

    /** A price set on creation and changed while a draft; only holders of {@code orders.price} may set it. */
    private static final EntityDefinition ORDERS = Entity.define("x.orders", "orders")
            .table("x_orders", "o")
            .scope(EntityScope.all())
            .field(text("title", "x.col.title").column("title").required().list(sortable()))
            .field(select("status", "x.col.status", List.of("draft", "posted"), null)
                    .column("status"))
            .field(text("code", "x.col.code")
                    .column("code")
                    .readonlyUnless("orders", "price")
                    .readonlyOnUpdate())
            .field(text("price", "x.col.price")
                    .column("price")
                    .readonlyUnless("orders", "price")
                    .readonlyWhen(FieldCondition.eq("status", "posted")))
            .section("main", "m", "title", "status", "code", "price")
            .defaultSort("title", Entity.Sort.ASC)
            .build();

    // ADR-0032, 4.4 and 5.2: read-only by the declaration or by the viewer's right, in one form.
    @Test
    void aFieldWithoutTheRightIsReadOnlyWhateverItsDeclaredKind() {
        signIn(Set.of("orders.view"));
        Map<String, FormFieldMeta> denied = fieldsOf(ORDERS);
        assertThat(denied.get("code").readonly()).isTrue();
        assertThat(denied.get("code").readonlyOnUpdate()).isNull();
        assertThat(denied.get("price").readonly()).isTrue();
        assertThat(denied.get("price").readonlyWhen()).isNull();

        signIn(Set.of("orders.view", "orders.price"));
        Map<String, FormFieldMeta> granted = fieldsOf(ORDERS);
        assertThat(granted.get("code").readonly()).isFalse();
        assertThat(granted.get("code").readonlyOnUpdate()).isTrue();
        assertThat(granted.get("price").readonly()).isFalse();
        assertThat(granted.get("price").readonlyWhen())
                .containsExactly(new ConditionItemMeta("status", "eq", List.of("posted"), null));
    }

    private static Map<String, FormFieldMeta> fieldsOf(EntityDefinition entity) {
        return FormMetaController.of(entity).fields().stream()
                .collect(Collectors.toMap(FormFieldMeta::key, Function.identity()));
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                7L, "viewer", "viewer@example.test", 1L, false, permissions, 1L, false, 1L, null));
    }
}
