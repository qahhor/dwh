package com.smartup24.cms.instance.common.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.2 (ADR-0032, 4.1): the list types of several references ({@code REF_SET}) and of a value that is
 * only there or not ({@code OBJECT}), the format of a list field and the enumeration read at request time.
 */
class QueryFieldTypesTest {

    private static final QueryRef TAGS = QueryRef.paged("/tags", "name");

    private static final QueryField TAG_IDS = new QueryField(
                    "tagIds",
                    "l",
                    QueryFieldType.REF_SET,
                    "array(select 1)",
                    true,
                    false,
                    true,
                    true,
                    List.of(),
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)
            .refersTo(TAGS)
            .formatted("multi_ref");

    private static final QueryField PHOTO = new QueryField(
                    "photo",
                    "l",
                    QueryFieldType.OBJECT,
                    "t.photo_id",
                    true,
                    false,
                    true,
                    true,
                    List.of(),
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)
            .formatted("image");

    private static final QueryList LIST = new QueryList(
            "t.list",
            "t",
            "view",
            "t.id",
            "t_items t",
            "t.id",
            List.of(QueryField.of("title", "l", QueryFieldType.TEXT, "t.title").asSortable(), TAG_IDS, PHOTO),
            "title");

    @Test
    void aSetOfKeysFiltersByOverlapAndEmptiness() {
        assertThat(TAG_IDS.ops()).extracting(QueryOp::wire).containsExactlyInAnyOrder("in", "empty", "not_empty");
        assertThat(TAG_IDS.ref()).isEqualTo(TAGS);
        assertThat(TAG_IDS.format()).isEqualTo("multi_ref");
        assertThat(QueryValues.parse(TAG_IDS, "5")).isEqualTo(5L);
        for (String bad : List.of("0", "-1", "x", "1.5")) {
            assertThatThrownBy(() -> QueryValues.parse(TAG_IDS, bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        QueryPlan.SqlFragment in = QueryCompiler.compile(
                        LIST, "[{\"field\":\"tagIds\",\"op\":\"in\",\"value\":[3,9]}]", null, null, null)
                .where();
        assertThat(in.sql()).contains("(array(select 1) && cast(array[:q_f0] as bigint[]))");
        assertThat(in.params()).containsEntry("q_f0", List.of(3L, 9L));
        assertThat(QueryCompiler.compile(LIST, "[{\"field\":\"tagIds\",\"op\":\"empty\"}]", null, null, null)
                        .where()
                        .sql())
                .contains("cardinality(array(select 1)) = 0");
        assertThat(QueryCompiler.compile(LIST, "[{\"field\":\"tagIds\",\"op\":\"not_empty\"}]", null, null, null)
                        .where()
                        .sql())
                .contains("cardinality(array(select 1)) > 0");
    }

    @Test
    void aValueOnlyThereOrNotTakesNoValue() {
        assertThat(PHOTO.ops()).extracting(QueryOp::wire).containsExactlyInAnyOrder("empty", "not_empty");
        assertThatThrownBy(() -> QueryValues.parse(PHOTO, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(QueryCompiler.compile(LIST, "[{\"field\":\"photo\",\"op\":\"not_empty\"}]", null, null, null)
                        .where()
                        .sql())
                .contains("t.photo_id is not null");
        assertThatThrownBy(() -> QueryValues.read(mock(ResultSet.class), "c", QueryFieldType.REF_SET))
                .isInstanceOf(IllegalStateException.class);
        assertThat(QueryFieldType.OBJECT.sortable()).isFalse();
        assertThat(QueryFieldType.REF_SET.wire()).isEqualTo("ref_set");
    }

    @Test
    void theDeclarationRefusesWhatTheTypesCannotHold() {
        assertThatThrownBy(() -> new QueryField(
                        "photo",
                        "l",
                        QueryFieldType.OBJECT,
                        "t.p",
                        true,
                        true,
                        false,
                        true,
                        List.of(),
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null))
                .hasMessageContaining("never sorted");
        assertThatThrownBy(() -> new QueryField(
                        "unit",
                        "l",
                        QueryFieldType.ENUM,
                        "t.u",
                        true,
                        false,
                        false,
                        true,
                        List.of(),
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null))
                .hasMessageContaining("enum values");
        assertThatThrownBy(() -> new QueryField(
                        "unit",
                        "l",
                        QueryFieldType.TEXT,
                        "t.u",
                        true,
                        false,
                        false,
                        true,
                        List.of(),
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("kg", "Kilogram")))
                .hasMessageContaining("enum labels");
        assertThatThrownBy(() -> PHOTO.withEnumeration(Map.of("kg", "Kilogram")))
                .hasMessageContaining("enumeration");
        assertThatThrownBy(() ->
                        QueryField.of("d", "l", QueryFieldType.DATE, "t.d").refersTo(TAGS))
                .hasMessageContaining("reference");

        QueryField unit = new QueryField(
                "unit",
                "l",
                QueryFieldType.ENUM,
                "t.u",
                true,
                false,
                false,
                true,
                List.of(),
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                QueryField.ENUM_FORMAT,
                null);
        assertThatThrownBy(() -> QueryValues.parse(unit, "kg"))
                .as("no items yet")
                .isInstanceOf(IllegalArgumentException.class);
        QueryField resolved = unit.withEnumeration(Map.of("kg", "Kilogram"));
        assertThat(QueryValues.parse(resolved, "kg")).isEqualTo("kg");
        assertThat(resolved.enumLabels()).containsEntry("kg", "Kilogram");
        assertThat(resolved.asHidden().asNullable().asNotFilterable().enumLabels())
                .containsEntry("kg", "Kilogram");
    }

    @Test
    void theRegistryGivesDeclaredFieldsTheirCurrentValues() {
        QueryField unit = new QueryField(
                "unit",
                "l",
                QueryFieldType.ENUM,
                "t.u",
                true,
                false,
                false,
                true,
                List.of(),
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                QueryField.ENUM_FORMAT,
                null);
        QueryList list = new QueryList(
                "t.units",
                "t",
                "view",
                "t.id",
                "t_items t",
                "t.id",
                List.of(
                        QueryField.of("title", "l", QueryFieldType.TEXT, "t.title")
                                .asSortable(),
                        unit),
                "title");
        QueryFieldResolver resolver = (owner, field) ->
                QueryField.ENUM_FORMAT.equals(field.format()) ? field.withEnumeration(Map.of("kg", "Kilogram")) : field;

        QueryList resolved =
                new QueryListRegistry(List.of(list), List.of(), List.of(), List.of(resolver)).get("t.units");

        assertThat(resolved.field("unit").orElseThrow().enumValues()).containsExactly("kg");
        assertThat(new QueryListRegistry(List.of(list))
                        .get("t.units")
                        .field("unit")
                        .orElseThrow()
                        .enumValues())
                .isEmpty();
    }
}
