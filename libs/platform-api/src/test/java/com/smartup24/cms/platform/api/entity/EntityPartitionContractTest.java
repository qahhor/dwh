package com.smartup24.cms.platform.api.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityFields;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.workflow.EntityWorkflow;
import org.junit.jupiter.api.Test;

class EntityPartitionContractTest {

    @Test
    void partitionIsAnExplicitServerOwnedBinding() {
        EntityModel model = base().partition("form_id", 41L).build().model();
        assertThat(model.partition()).isEqualTo(new EntityPartition("form_id", 41L));
    }

    @Test
    void invalidPartitionAndPropertyIdentifiersAreRejected() {
        assertThatThrownBy(() -> new EntityPartition("form_id;drop", 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityPartition("form_id", 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> text("value", "test.value").jsonProperty("payload", "x');drop"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sharedModelsRejectAssignmentsToThePartitionAndPayloadContainer() {
        assertThatThrownBy(() -> base().partition("name", 1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().partition("form_id", 1)
                        .field(text("other", "test.other").jsonProperty("form_id", "other"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().field(text("other", "test.other").jsonProperty("name", "other"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().field(text("other", "test.other").jsonProperty("attributes", "other"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().field(text("a", "test.a").jsonProperty("payload", "same"))
                        .field(text("b", "test.b").jsonProperty("payload", "same"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedPartitionFeaturesAndUnindexedSortAreRejected() {
        assertThatThrownBy(() ->
                        base().partition("form_id", 1).reference("code", "name").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().partition("form_id", 1)
                        .importKey("name")
                        .actions("create")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        base().partition("form_id", 1).customFields("TEST").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().partition("form_id", 1)
                        .field(text("other", "test.other").attribute("other"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> text("other", "test.other")
                        .jsonProperty("payload", "other")
                        .list(EntityFields.sortable())
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityFields.markdown("other", "test.other")
                        .jsonProperty("payload", "other")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonTextPropertiesKeepTheirOwnTypedSqlAndOrdinaryFormContract() {
        for (EntityFields.Builder builder : java.util.List.of(
                text("text", "test.text"),
                EntityFields.textarea("area", "test.area"),
                EntityFields.email("email", "test.email"),
                EntityFields.phone("phone", "test.phone"),
                EntityFields.url("url", "test.url"))) {
            var field = builder.jsonProperty("payload", "value").build();
            assertThat(field.sql("p")).isEqualTo("(p.payload->>'value')");
            assertThat(field.formField().attribute()).isNull();
        }
        // A fixed default is read while the key is absent; it reaches SQL as hexadecimal digits only (ADR-0041).
        var defaulted = text("text", "test.text")
                .jsonProperty("payload", "value")
                .defaultValue(FieldDefault.fixed("x');drop"))
                .build();
        assertThat(defaulted.sql("p"))
                .isEqualTo("(case when p.payload->'value' is null then convert_from(decode('7827293b64726f70', 'hex'),"
                        + " 'UTF8') else (p.payload->>'value') end)");
        var number = EntityFields.number("amount", "test.amount")
                .jsonProperty("payload", "amount")
                .defaultValue(FieldDefault.fixed("10"))
                .build();
        assertThat(number.sql("p")).endsWith(" end)::numeric");
        assertThat(base().build().model().partition()).isNull();
        assertThat(base().build().model().from()).isEqualTo("test_items t");
        assertThat(base().partition("form_id", Long.MAX_VALUE).build().model().from())
                .contains("form_id = 9223372036854775807");
    }

    /** A partitioned entity keeps a choice, an item of a reference list and a reference in its payload (ADR-0041). */
    @Test
    void partitionedJsonPropertiesHoldChoicesListItemsAndReferences() {
        var choice = EntityFields.select("result", "test.result", java.util.List.of("done", "later"), "test.result.")
                .jsonProperty("payload", "result")
                .build();
        assertThat(choice.sql("p")).isEqualTo("(p.payload->>'result')");
        var item = EntityFields.enumeration("kind", "test.kind", "studio.list.kinds")
                .jsonProperty("payload", "kind")
                .build();
        assertThat(item.sql("p")).isEqualTo("(p.payload->>'kind')");
        var reference = EntityFields.ref("client", "test.client")
                .target("studio.clients", "name")
                .jsonProperty("payload", "client")
                .build();
        assertThat(reference.sql("p")).isEqualTo("(p.payload->>'client')::bigint");
        var model = Entity.define("test.items", "test.items")
                .table("test_items", "t")
                .scope(EntityScope.all())
                .partition("form_id", 7)
                .field(text("name", "test.name").column("name").required())
                .field(EntityFields.select("result", "test.result", java.util.List.of("done"), null)
                        .jsonProperty("payload", "result"))
                .field(EntityFields.enumeration("kind", "test.kind", "studio.list.kinds")
                        .jsonProperty("payload", "kind"))
                .field(EntityFields.ref("client", "test.client")
                        .target("studio.clients", "name")
                        .jsonProperty("payload", "client"))
                .field(EntityFields.ref("unit", "test.unit")
                        .target("test.units", "name")
                        .column("org_unit_id"))
                .section("main", "test.main", "name", "result", "kind", "client", "unit")
                .defaultSort("name", Entity.Sort.ASC)
                .build()
                .model();
        assertThat(model.fields()).hasSize(5);
        assertThatThrownBy(() -> base().partition("form_id", 7)
                        .field(EntityFields.multiRef("many", "test.many")
                                .target("test.units", "name")
                                .link("test_links", "item_id", "unit_id"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A partitioned entity keeps the rows of its documents in a table of their own, keyed by the record, and a process
     * on a status held in its payload (ADR-0043); its own table cannot hold its rows.
     */
    @Test
    void partitionedDocumentsKeepRowsApartAndAProcessOnTheirPayload() {
        var lines = EntityCollection.of("items", "test.items.lines")
                .table("test_lines", "l")
                .parentColumn("record_id")
                .field(EntityFields.number("qty", "test.qty")
                        .jsonProperty("payload", "qty")
                        .required())
                .field(EntityFields.number("sum", "test.sum")
                        .jsonProperty("payload", "sum")
                        .readonly())
                .field(EntityFields.ref("product", "test.product")
                        .target("studio.products", "name")
                        .jsonProperty("payload", "product"))
                .maxRows(50)
                .build();
        var model = base().partition("form_id", 7)
                .field(EntityFields.select("status", "test.status", java.util.List.of("draft", "done"), "test.s.")
                        .jsonProperty("payload", "status")
                        .readonly()
                        .defaultValue(FieldDefault.fixed("draft")))
                .section("process", "test.process", "status")
                .collection(lines)
                .workflow(EntityWorkflow.on("status")
                        .state("draft", "test.s.draft")
                        .initial()
                        .state("done", "test.s.done")
                        .terminal()
                        .transition("finish", "draft", "done")
                        .build())
                .build()
                .model();
        assertThat(model.collections()).hasSize(1);
        assertThat(model.workflow()).isNotNull();
        assertThatThrownBy(() -> base().partition("form_id", 7)
                        .collection(EntityCollection.of("items", "test.items.lines")
                                .table("test_items", "l")
                                .parentColumn("record_id")
                                .field(EntityFields.number("qty", "test.qty").jsonProperty("payload", "qty"))
                                .build())
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another table");
    }

    @Test
    void aPartitionedEntityKeepsOneFileOrSeveralInAJsonProperty() {
        var model = base().partition("form_id", 1)
                .field(EntityFields.file("act", "test.act").jsonProperty("payload", "act"))
                .field(EntityFields.file("docs", "test.docs")
                        .jsonProperty("payload", "docs")
                        .maxItems(5)
                        .files(1024L, "application/pdf"))
                .section("files", "test.files", "act", "docs")
                .build()
                .model();
        assertThat(model.field("act").orElseThrow().sql("t")).isEqualTo("(t.payload->>'act')::uuid");
        assertThat(model.field("docs").orElseThrow().sql("t")).isEqualTo("(t.payload->'docs')");
        assertThatThrownBy(() -> EntityFields.file("docs", "test.docs")
                        .column("docs")
                        .maxItems(5)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON property");
        assertThatThrownBy(() -> EntityFields.image("photo", "test.photo")
                        .jsonProperty("payload", "photo")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityFields.json("data", "test.data", null)
                        .jsonProperty("payload", "data")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column");
    }

    private static Entity base() {
        return Entity.define("test.items", "test.items")
                .table("test_items", "t")
                .scope(EntityScope.all())
                .field(text("name", "test.name").column("name").required())
                .section("main", "test.main", "name")
                .defaultSort("name", Entity.Sort.ASC);
    }
}
