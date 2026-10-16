package com.smartup24.cms.platform.api.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.platform.api.entity.field.EntityFields;
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
        assertThat(base().build().model().partition()).isNull();
        assertThat(base().build().model().from()).isEqualTo("test_items t");
        assertThat(base().partition("form_id", Long.MAX_VALUE).build().model().from())
                .contains("form_id = 9223372036854775807");
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
