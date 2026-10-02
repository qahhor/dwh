package com.smartup24.cms.instance.common.entity.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.Entity;
import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.field.EntityFields;
import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The import key of every entity that declares IMPORT is a column a unique index guards (ADR-0032, 10.1): an upsert by
 * it finds one record at most. The declaration refuses a key that is not a text field of the form in a column, and
 * IMPORT without a key or without a create or an update.
 */
class EntityImportDeclaredTest extends EmbeddedPostgresTest {

    @Autowired
    private List<EntityDefinition> entities;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("10.1: the import key of every importable entity has a unique index of its own")
    void everyImportKeyIsUnique() {
        List<EntityDefinition> importable = entities.stream()
                .filter(entity -> entity.capabilities().contains(EntityCapability.IMPORT))
                .toList();
        assertThat(importable).as("the importable entities").isNotEmpty();
        for (EntityDefinition entity : importable) {
            EntityModel model = Objects.requireNonNull(entity.model());
            String key = Objects.requireNonNull(model.importing()).key();
            String column = ((FieldSource.Column) model.field(key).orElseThrow().source()).name();
            Long indexes = jdbc.sql("""
                            select count(*)
                              from pg_index i
                              join pg_class t on t.oid = i.indrelid
                              join pg_attribute a on a.attrelid = t.oid and a.attnum = i.indkey[0]
                             where t.relname = :table and i.indisunique and i.indnatts = 1 and a.attname = :column
                            """)
                    .param("table", model.table())
                    .param("column", column)
                    .query(Long.class)
                    .single();
            assertThat(indexes)
                    .as("a unique index on %s.%s, the import key of %s", model.table(), column, entity.code())
                    .isPositive();
        }
    }

    @Test
    @DisplayName("10.1: the declaration refuses a key that is no text column, and IMPORT without a key or a write")
    void declarationRefusesBadKeys() {
        assertThatThrownBy(() -> base().importKey("missing").actions("create").build())
                .hasMessageContaining("no import key field");
        assertThatThrownBy(() -> base().importKey("amount").actions("create").build())
                .hasMessageContaining("is a text field of the form");
        assertThatThrownBy(() -> base().importKey("code").build()).hasMessageContaining("IMPORT needs");
        assertThatThrownBy(() -> base().capabilities(EntityCapability.IMPORT)
                        .actions("create")
                        .build())
                .hasMessageContaining("IMPORT needs");
        assertThatThrownBy(() -> Entity.define("test.forms", "test.forms")
                        .importKey("code")
                        .build())
                .hasMessageContaining("an import writes the records of its table");
        EntityDefinition fine = base().importKey("code").actions("update").build();
        assertThat(Objects.requireNonNull(fine.model()).importing()).isEqualTo(new EntityImportSpec("code"));
        assertThat(fine.capabilities()).contains(EntityCapability.IMPORT);
    }

    private static Entity base() {
        return Entity.define("test.items", "test.items")
                .table("test_items", "ti")
                .scope(EntityScope.all())
                .field(EntityFields.text("code", "test.code").column("code").required())
                .field(EntityFields.number("amount", "test.amount").column("amount"))
                .section("main", "entity.section.main", "code", "amount")
                .defaultSort("code", Entity.Sort.ASC);
    }
}
