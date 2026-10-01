package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.instant;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityDefinition.FormSection;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 5.1 (ADR-0032, 3.2–3.4): the entity builder and the list derived from the entity's fields. */
class EntityListsTest {

    private static EntityDefinition items() {
        return Entity.define("x.items", "x")
                .table("x_items", "t")
                .field(text("name", "x.col.name")
                        .column("name")
                        .required()
                        .list(sortable().searchable()))
                .field(select("status", "x.col.status", List.of("active", "archived"), "x.status.")
                        .column("status"))
                .field(instant("modifiedAt", "x.col.modified_at")
                        .system(SystemColumn.MODIFIED_AT)
                        .list(sortable()))
                .section("main", "entity.section.main", "name", "status")
                .actions("create", "update")
                .action("archive", "update")
                .actions("delete")
                .defaultSort("modifiedAt", Entity.Sort.DESC)
                .capabilities(EntityCapability.HISTORY, EntityCapability.EXPORT)
                .build();
    }

    @Test
    void theBuilderDerivesTheFormAndNamesTheList() {
        EntityDefinition entity = items();

        assertThat(entity.listCode()).isEqualTo("x.items");
        assertThat(entity.auditTable()).as("history is written under the table").isEqualTo("x_items");
        assertThat(entity.fields()).extracting(FormField::key).containsExactly("name", "status");
        assertThat(entity.actions())
                .extracting(EntityDefinition.EntityAction::code)
                .containsExactly("create", "update", "archive", "delete");
        assertThat(entity.action("archive").orElseThrow().permission()).isEqualTo("update");
        assertThat(entity.model().table()).isEqualTo("x_items");
    }

    @Test
    void theListReadsEveryFieldUnderItsKey() {
        QueryList list = EntityLists.queryList(items());

        assertThat(list.code()).isEqualTo("x.items");
        assertThat(list.form()).isEqualTo("x");
        assertThat(list.action()).isEqualTo("view");
        assertThat(list.from()).isEqualTo("x_items t");
        assertThat(list.idSql()).isEqualTo("t.id");
        assertThat(list.defaultSort()).isEqualTo("modifiedAt");
        assertThat(list.defaultDescending()).isTrue();
        assertThat(list.customEntity()).isNull();
        assertThat(list.select())
                .isEqualTo("t.id as \"id\", t.revision as \"revision\", t.created_at as \"createdAt\","
                        + " t.created_by as \"createdBy\", t.modified_at as \"modifiedAt\","
                        + " t.modified_by as \"modifiedBy\", t.name as \"name\", t.status as \"status\","
                        + " t.attributes::text as \"attributes\"");
        assertThat(list.attributesSql()).as("no custom fields offered").isNull();
        assertThat(list.fields()).extracting(QueryField::key).containsExactly("name", "status", "modifiedAt");
    }

    @Test
    void theNotesListCarriesItsCustomFieldsAndAttributes() {
        QueryList notes = EntityLists.queryList(MsNoteEntity.DEFINITION);

        assertThat(notes.customEntity()).isEqualTo("NOTE");
        assertThat(notes.attributesSql()).isEqualTo("n.attributes");
        assertThat(notes.select()).endsWith("n.attributes::text as \"attributes\"");
        assertThat(notes.field("rank").orElseThrow().sortable()).isTrue();
        assertThat(new EntityLists(List.of(MsNoteEntity.DEFINITION, formOnly())).lists())
                .extracting(QueryList::code)
                .containsExactly("ms.notes");
    }

    @Test
    void theRecordsOwnKeysAreNotFieldKeys() {
        assertThatThrownBy(() -> Entity.define("x.items", "x")
                        .table("x_items", "t")
                        .field(text("attributes", "x.col.a").column("attrs").list(sortable()))
                        .section("main", "m", "attributes")
                        .defaultSort("attributes", Entity.Sort.ASC)
                        .build())
                .hasMessageContaining("record's own");
        assertThatThrownBy(() -> Entity.define("x.items", "x")
                        .table("x_items", "t")
                        .field(text("createdBy", "x.col.c").column("author").list(sortable()))
                        .section("main", "m", "createdBy")
                        .defaultSort("createdBy", Entity.Sort.ASC)
                        .build())
                .hasMessageContaining("record's own");
    }

    @Test
    void anEntityHasAListOnlyThroughItsFields() {
        EntityDefinition entity = items();
        FormField name = entity.fields().getFirst();
        List<FormSection> layout = List.of(new FormSection("main", "m", List.of("name")));

        assertThatThrownBy(() -> new EntityDefinition(
                        "x.items",
                        "x",
                        "x.items",
                        null,
                        null,
                        null,
                        null,
                        List.of(name),
                        layout,
                        List.of(),
                        Set.of(),
                        null))
                .hasMessageContaining("EntityField");
        assertThatThrownBy(() -> new EntityDefinition(
                        "x.items",
                        "x",
                        "x.items",
                        null,
                        null,
                        null,
                        null,
                        List.of(FormField.of("other", "o", name.type())),
                        List.of(new FormSection("main", "m", List.of("other"))),
                        List.of(),
                        Set.of(),
                        entity.model()))
                .hasMessageContaining("come from its model");
        assertThatThrownBy(() -> Entity.define("x.items", "x")
                        .table("x_items", "t")
                        .field(text("name", "l").column("name").list(sortable()))
                        .section("main", "m", "name")
                        .build())
                .hasMessageContaining("default sort");
    }

    @Test
    void theRegistryTakesTheEntityListsAndRefusesASecondDeclaration() {
        EntityLists source = new EntityLists(List.of(items()));
        QueryListRegistry registry = new QueryListRegistry(List.of(), List.of(source), List.of());

        assertThat(registry.get("x.items").fields()).hasSize(3);
        assertThatThrownBy(() ->
                        new QueryListRegistry(List.of(EntityLists.queryList(items())), List.of(source), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x.items");
    }

    private static EntityDefinition formOnly() {
        return Entity.define("x.settings", "x")
                .field(text("motto", "x.col.motto").column("motto").formOnly())
                .section("main", "m", "motto")
                .build();
    }
}
