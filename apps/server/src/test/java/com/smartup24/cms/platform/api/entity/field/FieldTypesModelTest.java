package com.smartup24.cms.platform.api.entity.field;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.email;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.enumeration;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.file;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.image;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.json;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.money;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.multiRef;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityListFields;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.2 (ADR-0032, 4.1–4.4): what each new type needs and refuses, where its value lives, and the form
 * flags — conditions, read-only kinds and defaults.
 */
class FieldTypesModelTest {

    private static final QueryRef TAGS = QueryRef.paged("/entities/tags", "name");

    @Test
    void moneyLivesInItsColumnsAndFiltersByAmountAndCurrency() {
        EntityField total = money("total", "l", "UZS", "USD")
                .money("total_amount", "total_currency")
                .list(sortable())
                .build();
        EntityField fixed =
                money("price", "l", "UZS").money("price_amount", null).build();

        List<QueryField> listed = EntityListFields.queryFields(total, "o");
        assertThat(listed).extracting(QueryField::key).containsExactly("total", "totalCurrency");
        assertThat(listed.get(0).sql()).isEqualTo("o.total_amount");
        assertThat(listed.get(0).type()).isEqualTo(QueryFieldType.NUMBER);
        assertThat(listed.get(0).format()).isEqualTo("money");
        assertThat(listed.get(1).sql()).isEqualTo("o.total_currency");
        assertThat(listed.get(1).enumValues()).containsExactly("UZS", "USD");
        assertThat(EntityListFields.queryFields(fixed, "o").get(1).sql()).isEqualTo("'UZS'");
        assertThat(total.formField().params().currencies()).containsExactly("UZS", "USD");

        assertThatThrownBy(
                        () -> money("total", "l", "UZS", "USD").money("a", null).build())
                .hasMessageContaining("one currency");
        assertThatThrownBy(() -> money("total", "l").money("a", "c").build()).hasMessageContaining("currencies");
        assertThatThrownBy(() -> money("total", "l", "uzs").money("a", "c").build())
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> money("total", "l", "UZS").column("total").build())
                .hasMessageContaining("money columns");
        assertThatThrownBy(() -> number("qty", "l").money("a", "c").build()).hasMessageContaining("only money");
    }

    @Test
    void severalReferencesLiveInALinkTable() {
        EntityField tags = multiRef("tagIds", "l", TAGS)
                .link("ex_order_tags", "order_id", "tag_id")
                .maxItems(5)
                .build();

        QueryField list = EntityListFields.queryField(tags, "o");
        assertThat(list.type()).isEqualTo(QueryFieldType.REF_SET);
        assertThat(list.sql())
                .isEqualTo("array(select l.tag_id from ex_order_tags l where l.order_id = o.id"
                        + " order by l.position, l.tag_id)");
        assertThat(list.ops()).extracting(op -> op.wire()).containsExactlyInAnyOrder("in", "empty", "not_empty");
        assertThat(list.ref()).isEqualTo(TAGS);
        assertThat(tags.formField().params().maxItems()).isEqualTo(5);

        assertThatThrownBy(() -> multiRef("tagIds", "l", TAGS).column("tag_ids").build())
                .hasMessageContaining("link table");
        assertThatThrownBy(() -> multiRef("tagIds", "l", TAGS)
                        .link("t", "o", "x")
                        .list(sortable())
                        .build())
                .hasMessageContaining("never sorted");
        assertThatThrownBy(() -> new FieldSource.Link("t; drop", "o", "x")).hasMessageContaining("identifier");
    }

    @Test
    void filesAndJsonAreOnlyThereOrNot() {
        EntityField photo = image("photo", "l").column("photo_id").build();
        EntityField act = file("act", "l")
                .column("act_id")
                .files(1024L, "application/pdf")
                .build();
        EntityField extra =
                json("extra", "l", FieldOptions.JsonRoot.ARRAY).column("extra").build();

        assertThat(EntityListFields.queryField(photo, "t").type()).isEqualTo(QueryFieldType.OBJECT);
        assertThat(EntityListFields.queryField(photo, "t").ops())
                .extracting(op -> op.wire())
                .containsExactlyInAnyOrder("empty", "not_empty");
        assertThat(photo.formField().params().contentTypes()).containsExactly("image/png", "image/jpeg", "image/webp");
        assertThat(act.formField().params().contentTypes()).containsExactly("application/pdf");
        assertThat(act.formField().params().maxBytes()).isEqualTo(1024L);
        assertThat(extra.formField().params().jsonRoot()).isEqualTo(FieldOptions.JsonRoot.ARRAY);

        assertThatThrownBy(() ->
                        image("photo", "l").column("p").files(null, "image/gif").build())
                .hasMessageContaining("PNG, JPEG or WebP");
        assertThatThrownBy(() -> file("act", "l").attribute("act").build()).hasMessageContaining("column");
        assertThatThrownBy(() ->
                        json("extra", "l", null).column("e").list(sortable()).build())
                .hasMessageContaining("never sorted");
    }

    @Test
    void anEnumerationNamesItsReference() {
        EntityField unit = enumeration("unit", "l", "ex.units").column("unit").build();

        QueryField list = EntityListFields.queryField(unit, "t");
        assertThat(list.type()).isEqualTo(QueryFieldType.ENUM);
        assertThat(list.enumValues()).as("read at request time").isEmpty();
        assertThat(list.format()).isEqualTo(QueryField.ENUM_FORMAT);
        assertThat(list.withEnumeration(Map.of("kg", "Kilogram")).enumValues()).containsExactly("kg");
        assertThat(unit.formField().params().enumeration()).isEqualTo("ex.units");
    }

    @Test
    void aSecretIsNoEntityField() {
        for (String key : List.of("password", "apiKey", "clientSecret", "privateKeyPem", "accessToken")) {
            assertThatThrownBy(() -> text(key, "l").column("x").build()).as(key).hasMessageContaining("ADR-0029");
        }
        assertThat(text("tokenCount", "l").column("token_count").build().key()).isEqualTo("tokenCount");
    }

    @Test
    void aConditionHoldsOverTheFormValues() {
        FieldCondition wholesale = FieldCondition.eq("kind", "wholesale");
        FieldCondition either = FieldCondition.any(
                new FieldCondition.Clause("kind", FieldCondition.Op.IN, List.of("a", "b")),
                new FieldCondition.Clause("vip", FieldCondition.Op.EQ, List.of("true")));
        Map<String, Object> values = new HashMap<>();
        values.put("kind", "wholesale");
        values.put("vip", true);

        assertThat(wholesale.test(values)).isTrue();
        assertThat(FieldCondition.ne("kind", "wholesale").test(values)).isFalse();
        assertThat(either.test(values)).isTrue();
        assertThat(either.and(FieldCondition.empty("ownerId")).test(values)).isTrue();
        assertThat(FieldCondition.notEmpty("ownerId").test(values)).isFalse();
        assertThat(FieldCondition.in("kind", "retail").test(values)).isFalse();
        assertThat(either.fields()).containsExactly("kind", "vip");
        assertThatThrownBy(() -> new FieldCondition.Clause("kind", FieldCondition.Op.EQ, List.of()))
                .hasMessageContaining("takes");
        assertThatThrownBy(() -> new FieldCondition(List.of())).hasMessageContaining("clause");
        assertThatThrownBy(() -> new FieldCondition(List.of(List.of()))).hasMessageContaining("group");
    }

    @Test
    void readOnlyKindsAndDefaults() {
        Map<String, Object> posted = Map.of("status", "posted");
        assertThat(FieldReadonly.ALWAYS.applies(true, Map.of())).isTrue();
        assertThat(FieldReadonly.ON_UPDATE.applies(true, Map.of())).isFalse();
        assertThat(FieldReadonly.ON_UPDATE.applies(false, posted)).isTrue();
        assertThat(FieldReadonly.when(FieldCondition.eq("status", "posted")).applies(false, posted))
                .isTrue();
        assertThat(FieldReadonly.when(FieldCondition.eq("status", "posted")).applies(true, posted))
                .isFalse();
        assertThatThrownBy(() -> new FieldReadonly(FieldReadonly.Mode.WHEN, null))
                .hasMessageContaining("WHEN");

        FieldDefault.Sequence sequence = new FieldDefault.Sequence("ex_orders_number_seq", "ORD-{000000}");
        assertThat(sequence.format(42)).isEqualTo("ORD-000042");
        assertThat(sequence.format(1_234_567)).isEqualTo("ORD-1234567");
        assertThat(FieldDefault.today().kind()).isEqualTo("today");
        assertThat(FieldDefault.now().value()).isNull();
        assertThat(FieldDefault.currentUser().kind()).isEqualTo("current_user");
        assertThat(FieldDefault.currentOrgUnit().kind()).isEqualTo("current_org_unit");
        assertThat(FieldDefault.currentOrgUnit().value()).isNull();
        assertThat(FieldDefault.fixed("x").value()).isEqualTo("x");
        assertThatThrownBy(() -> FieldDefault.sequence("bad name", "{0}")).hasMessageContaining("sequence name");
        assertThatThrownBy(() -> FieldDefault.sequence("s", "ORD-")).hasMessageContaining("{000000}");

        EntityField number = text("number", "l")
                .column("number")
                .defaultValue(FieldDefault.sequence("ex_orders_number_seq", "ORD-{000000}"))
                .build();
        assertThat(number.formField().flags().readonly()).isEqualTo(FieldReadonly.ALWAYS);
        assertThatThrownBy(() -> text("number", "l")
                        .column("number")
                        .readonlyOnUpdate()
                        .defaultValue(FieldDefault.sequence("s", "{00}"))
                        .build())
                .hasMessageContaining("sequence");
        assertThatThrownBy(
                        () -> text("x", "l").column("x").readonly().required().build())
                .hasMessageContaining("default");
        assertThatThrownBy(() -> new FormFlags(FieldReadonly.ON_UPDATE, null, null, true))
                .hasMessageContaining("computed");
        assertThatThrownBy(() -> number("x", "l").computed("t.a").required().build())
                .hasMessageContaining("computed");
        assertThatThrownBy(() -> select("kind", "l", List.of("a"), null)
                        .column("kind")
                        .visibleWhen(FieldCondition.eq("kind", "a"))
                        .build())
                .hasMessageContaining("own value");
        assertThatThrownBy(() -> email("mail", "l").expression("t.a").readonly().build())
                .hasMessageContaining("form rules");
    }
}
