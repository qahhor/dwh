package com.smartup24.cms.instance.common.entity.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.FieldTypesFixture;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 6 of ADR-0032, 6.3 and its "mass assignment" threat (ADR-0032, 12): the body is read field by field against
 * the declaration — only written fields of the form are taken; the record's own properties, labels, actions, list-only
 * and server-written fields and unknown keys are {@code unknown_field}; a value of the wrong JSON type is
 * {@code invalid}.
 */
class EntityRequestReaderTest {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Test
    @DisplayName("ADR-0032, 12: the record's own properties, labels, actions and unknown keys are unknown fields")
    void onlyWrittenFieldsOfTheFormAreTaken() {
        EntityRequestReader.Body body = EntityRequestReader.read(MsNoteEntity.DEFINITION, json("""
                {"title": "Plan", "id": 5, "revision": 9, "createdBy": 1, "modifiedAt": "x", "archived": true,
                 "archivedAt": "x", "labels": {}, "actions": [], "rank": "1", "nope": 1}
                """));

        assertThat(body.values()).containsOnlyKeys("title");
        assertThat(body.errors())
                .extracting(FieldErrorItem::field)
                .containsExactlyInAnyOrder(
                        "id",
                        "revision",
                        "createdBy",
                        "modifiedAt",
                        "archived",
                        "archivedAt",
                        "labels",
                        "actions",
                        "rank",
                        "nope");
        assertThat(body.errors()).extracting(FieldErrorItem::code).containsOnly("unknown_field");
    }

    @Test
    @DisplayName("ADR-0032, 6.3, step 6: a value of a JSON type the field never takes is invalid")
    void aWrongJsonTypeIsInvalid() {
        EntityRequestReader.Body body = EntityRequestReader.read(MsNoteEntity.DEFINITION, json("""
                {"title": 5, "isPinned": "yes", "color": ["blue"], "attributes": "x"}
                """));

        assertThat(body.values()).isEmpty();
        assertThat(body.errors())
                .extracting(item -> item.field() + ":" + item.code() + ":" + item.messageKey())
                .containsExactlyInAnyOrder(
                        "title:invalid:error.field.value_type",
                        "isPinned:invalid:error.field.value_type",
                        "color:invalid:error.field.value_type",
                        "attributes:invalid:error.field.value_type");
    }

    @Test
    @DisplayName("every type takes its JSON form, null clears, a fraction is exact, attributes go aside")
    void everyTypeTakesItsJsonForm() {
        EntityRequestReader.Body body = EntityRequestReader.read(FieldTypesFixture.DEFINITION, json("""
                {"title": "T", "qty": 12.50, "active": true, "ownerId": 7, "tagIds": [1, 2],
                 "total": {"amount": "1.00", "currency": "UZS"}, "extra": {"a": [1]}, "photo": null,
                 "region": "north", "attributes": {"level": "3", "cfX": "y"}}
                """));

        assertThat(body.errors())
                .extracting(item -> item.field() + ":" + item.code())
                .as("a free value in attributes of an entity without custom fields")
                .containsExactly("attributes.cfX:unknown_field");
        assertThat((BigDecimal) body.values().get("qty")).isEqualByComparingTo("12.5");
        assertThat(body.values().get("ownerId")).isEqualTo(7L);
        assertThat(body.values().get("tagIds")).isEqualTo(List.of(1L, 2L));
        assertThat(body.values()).containsKey("photo").doesNotContainKey("region");
        assertThat(body.attributes())
                .containsEntry("region", "north")
                .containsEntry("level", "3")
                .doesNotContainKey("cfX");
    }

    @Test
    @DisplayName("a body that is not a JSON object is refused")
    void aBodyIsAnObject() {
        for (String text : List.of("[1]", "\"x\"", "null")) {
            assertThatThrownBy(() -> EntityRequestReader.read(MsNoteEntity.DEFINITION, json(text)))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.getMessageKey()).isEqualTo("error.common.entity_body_invalid"));
        }
        assertThat(EntityRequestReader.params(null)).isEmpty();
        assertThat(EntityRequestReader.params(json("{\"reason\": \"late\"}"))).containsEntry("reason", "late");
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
