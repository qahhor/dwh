package com.smartup24.cms.platform.api.entity.field;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.date;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.enumeration;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.hidden;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.listed;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.markdown;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.searchable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.textarea;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.time;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityListFields;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 5.1 (ADR-0032, 3.1): one entity field, its form field and its list field derived from it. */
class EntityFieldTest {

    @Test
    void aWrittenFieldIsOnTheFormAndInTheListAlike() {
        EntityField title = text("title", "x.col.title")
                .column("title")
                .required()
                .length(1, 255)
                .matching("[A-Za-z ]+")
                .list(sortable().searchable())
                .build();

        FormField form = title.formField();
        QueryField list = EntityListFields.queryField(title, "t");

        assertThat(form).isNotNull();
        assertThat(form.key()).isEqualTo("title");
        assertThat(form.type()).isEqualTo(FieldType.TEXT);
        assertThat(form.required()).isTrue();
        assertThat(form.minLength()).isEqualTo(1);
        assertThat(form.maxLength()).isEqualTo(255);
        assertThat(form.pattern()).isEqualTo("[A-Za-z ]+");
        assertThat(form.attribute()).isNull();
        assertThat(list).isNotNull();
        assertThat(list.key()).isEqualTo("title");
        assertThat(list.labelKey()).isEqualTo("x.col.title");
        assertThat(list.type()).isEqualTo(QueryFieldType.TEXT);
        assertThat(list.sql()).isEqualTo("t.title");
        assertThat(list.sortable()).isTrue();
        assertThat(list.searchable()).isTrue();
        assertThat(list.defaultVisible()).isTrue();
        assertThat(title.exported()).isTrue();
        assertThat(title.history()).isTrue();
        assertThat(title.importable()).isTrue();
    }

    @Test
    void aSelectIsAnEnumerationWithTheSameOptionsInTheList() {
        EntityField color = select("color", "x.col.color", List.of("red", "blue"), "x.color_")
                .column("color")
                .build();

        assertThat(color.formField().options()).containsExactly("red", "blue");
        assertThat(color.formField().optionLabelPrefix()).isEqualTo("x.color_");
        QueryField list = EntityListFields.queryField(color, "t");
        assertThat(list.type()).isEqualTo(QueryFieldType.ENUM);
        assertThat(list.enumValues()).containsExactly("red", "blue");
        assertThat(list.enumLabelPrefix()).isEqualTo("x.color_");
    }

    @Test
    void aReferenceNamesItsSourceOnTheFormAndInTheList() {
        QueryRef users = QueryRef.paged("/iam/users", "name");
        EntityField owner =
                ref("ownerId", "x.col.owner", users).column("owner_id").build();

        assertThat(owner.formField().type()).isEqualTo(FieldType.REF);
        assertThat(owner.formField().ref()).isEqualTo(users);
        assertThat(EntityListFields.queryField(owner, "t").type()).isEqualTo(QueryFieldType.NUMBER);
        assertThat(EntityListFields.queryField(owner, "t").ref()).isEqualTo(users);
        assertThatThrownBy(() -> new EntityField(
                        "ownerId",
                        "l",
                        null,
                        FieldType.REF,
                        new FieldSource.Column("owner_id"),
                        FormPart.OPTIONAL,
                        null,
                        FieldAccess.OPEN,
                        FieldOptions.NONE,
                        false,
                        true,
                        true))
                .hasMessageContaining("reference");
    }

    @Test
    void anExpressionAndASystemColumnAreInTheListOnlyAndAComputedValueIsShownReadOnly() {
        EntityField rank = text("rank", "x.col.rank")
                .expression("(t.a || t.b)")
                .listOnly(sortable().notFilterable().hidden())
                .build();
        EntityField changed = instant("modifiedAt", "x.col.modified_at")
                .system(SystemColumn.MODIFIED_AT)
                .list(sortable())
                .build();
        EntityField total =
                number("total", "x.col.total").computed("t.qty * t.price").build();

        assertThat(rank.formField()).isNull();
        assertThat(EntityListFields.queryField(rank, "t").sql()).isEqualTo("(t.a || t.b)");
        assertThat(EntityListFields.queryField(rank, "t").ops()).isEmpty();
        assertThat(EntityListFields.queryField(rank, "t").defaultVisible()).isFalse();
        assertThat(rank.history()).isFalse();
        assertThat(changed.formField()).isNull();
        assertThat(EntityListFields.queryField(changed, "t").type()).isEqualTo(QueryFieldType.INSTANT);
        assertThat(EntityListFields.queryField(changed, "t").sql()).isEqualTo("t.modified_at");
        assertThat(total.formField().computed()).isTrue();
        assertThat(total.formField().flags().readonly()).isEqualTo(FieldReadonly.ALWAYS);
        assertThat(EntityListFields.queryField(total, "t").sql()).isEqualTo("t.qty * t.price");
        assertThat(total.importable()).isFalse();
    }

    @Test
    void formOnlyAndListOnlyFieldsHaveOnePart() {
        EntityField note =
                textarea("note", "x.col.note").column("note").formOnly().build();
        EntityField done =
                bool("done", "x.col.done").column("done").listOnly(listed()).build();

        assertThat(EntityListFields.queryField(note, "t")).isNull();
        assertThat(note.exported()).isFalse();
        assertThat(done.formField()).isNull();
        assertThat(EntityListFields.queryField(done, "t").type()).isEqualTo(QueryFieldType.BOOLEAN);
    }

    @Test
    void anAttributeHoldsTextAndIsNeverSorted() {
        EntityField region = text("region", "x.col.region")
                .attribute("region")
                .list(hidden())
                .build();

        assertThat(region.formField().attribute()).isEqualTo("region");
        assertThat(EntityListFields.queryField(region, "t").attribute()).isEqualTo("region");
        assertThat(EntityListFields.queryField(region, "t").sql()).isEqualTo("(t.attributes->>'region')");
        assertThat(EntityListFields.queryField(
                                date("due", "x.col.due").attribute("due").build(), "t")
                        .sql())
                .as("a scalar of another type is cast, only a value of its shape (plan 10/10, item 5.2)")
                .isEqualTo("(case when (t.attributes->>'due') ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'"
                        + " then (t.attributes->>'due')::date end)");
        assertThatThrownBy(() -> enumeration("unit", "x.col.unit", "ex.units")
                        .attribute("unit")
                        .build())
                .hasMessageContaining("attribute");
        assertThatThrownBy(() -> text("code", "x.col.code")
                        .attribute("code")
                        .list(sortable())
                        .build())
                .hasMessageContaining("never sorted");
    }

    @Test
    void theDeclarationRefusesWhatCannotHold() {
        assertThatThrownBy(() -> text("Bad-key", "l").column("x").build()).hasMessageContaining("key");
        assertThatThrownBy(() -> text("name", "l").build()).hasMessageContaining("value lives");
        assertThatThrownBy(() -> text("name", "l").column("a").column("b")).hasMessageContaining("one source");
        assertThatThrownBy(() -> text("name", "l")
                        .column("name")
                        .listOnly(listed())
                        .formOnly()
                        .build())
                .hasMessageContaining("neither");
        assertThatThrownBy(() -> text("rank", "l").expression("t.a").required().build())
                .hasMessageContaining("form rules");
        assertThatThrownBy(() ->
                        instant("changed", "l").system(SystemColumn.MODIFIED_AT).build())
                .hasMessageContaining("modifiedAt");
        assertThatThrownBy(() -> new EntityField(
                        "rank",
                        "l",
                        null,
                        FieldType.TEXT,
                        new FieldSource.Expression("t.a"),
                        FormPart.OPTIONAL,
                        null,
                        FieldAccess.OPEN,
                        FieldOptions.NONE,
                        false,
                        false,
                        false))
                .hasMessageContaining("only a column");
        assertThatThrownBy(() -> new EntityField(
                        "color",
                        "l",
                        null,
                        FieldType.SELECT,
                        new FieldSource.Column("color"),
                        FormPart.OPTIONAL,
                        null,
                        FieldAccess.OPEN,
                        FieldOptions.NONE,
                        false,
                        true,
                        true))
                .hasMessageContaining("options");
    }

    @Test
    void sourcesTakeOnlyDeclaredIdentifiersAndPlainExpressions() {
        assertThatThrownBy(() -> new FieldSource.Column("title; drop table x")).hasMessageContaining("identifier");
        assertThatThrownBy(() -> new FieldSource.Attribute("Region")).hasMessageContaining("identifier");
        assertThatThrownBy(() -> new FieldSource.Expression("1; delete from x")).hasMessageContaining("expression");
        assertThatThrownBy(() -> new FieldSource.Computed("a -- b")).hasMessageContaining("expression");
        assertThatThrownBy(() -> new FieldSource.Expression(" ")).hasMessageContaining("expression");
        assertThat(new FieldSource.Column("title").writable()).isTrue();
        assertThat(new FieldSource.Attribute("region").writable()).isTrue();
        assertThat(new FieldSource.Computed("a + b").writable()).isFalse();
        assertThat(new FieldSource.SystemValue(SystemColumn.CREATED_BY).sql("n"))
                .isEqualTo("n.created_by");
        assertThat(SystemColumn.CREATED_BY.key()).isEqualTo("createdBy");
    }

    @Test
    void everyKindHasItsListType() {
        assertThat(EntityListFields.listType(FieldType.MARKDOWN)).isEqualTo(QueryFieldType.TEXT);
        assertThat(EntityListFields.listType(FieldType.NUMBER)).isEqualTo(QueryFieldType.NUMBER);
        assertThat(EntityListFields.listType(FieldType.DATE)).isEqualTo(QueryFieldType.DATE);
        assertThat(EntityListFields.listType(FieldType.DATETIME)).isEqualTo(QueryFieldType.INSTANT);
        assertThat(EntityListFields.listType(FieldType.TIME)).isEqualTo(QueryFieldType.TIME);
        assertThat(EntityListFields.listType(FieldType.BOOLEAN)).isEqualTo(QueryFieldType.BOOLEAN);
        assertThat(EntityListFields.listType(FieldType.SELECT)).isEqualTo(QueryFieldType.ENUM);
        assertThat(EntityListFields.listType(FieldType.REF)).isEqualTo(QueryFieldType.NUMBER);
        assertThat(EntityListFields.queryField(
                                time("callTime", "l").column("call_time").build(), "t")
                        .type())
                .isEqualTo(QueryFieldType.TIME);
        assertThat(EntityListFields.queryField(
                                markdown("body", "l")
                                        .column("body")
                                        .list(searchable())
                                        .build(),
                                "t")
                        .searchable())
                .isTrue();
    }

    @Test
    void numberRulesAndPartsAreValues() {
        EntityField amount = number("amount", "l")
                .column("amount")
                .range(BigDecimal.ZERO, BigDecimal.TEN)
                .build();

        assertThat(amount.formField().min()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(amount.formField().max()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(FormPart.OPTIONAL.asRequired().required()).isTrue();
        assertThat(FormPart.OPTIONAL
                        .withRules(FieldRules.NONE.length(1, 2))
                        .rules()
                        .maxLength())
                .isEqualTo(2);
        assertThat(ListPart.LISTED.sortable()).isEqualTo(sortable()).hasSameHashCodeAs(sortable());
        assertThat(ListPart.LISTED.nullable().isNullable()).isTrue();
        assertThat(ListPart.LISTED).isNotEqualTo(hidden()).hasToString(ListPart.LISTED.toString());
        assertThat(ListPart.LISTED.toString()).contains("filterable=true");
    }

    @Test
    void aFieldRightNamesItsFormAndAction() {
        assertThatThrownBy(() -> new FieldAccess("md.users", null, null, null)).hasMessageContaining("both");
        EntityField phone = new EntityField(
                "phone",
                "l",
                null,
                FieldType.TEXT,
                new FieldSource.Column("phone"),
                null,
                ListPart.LISTED,
                new FieldAccess("md.users", "view_contacts", null, null),
                FieldOptions.NONE,
                true,
                true,
                false);

        assertThat(EntityListFields.queryField(phone, "t").requiredForm()).isEqualTo("md.users");
        assertThat(EntityListFields.queryField(phone, "t").requiredAction()).isEqualTo("view_contacts");
    }
}
