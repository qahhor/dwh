package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.date;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.money;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.ref;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 6.1: the entity declarations the application runs and the schema every migration produced agree —
 * each declared table, column, link table and collection table exists. The same comparison is the entry point of
 * {@code cms migration diff} (tools/cms-cli): with {@code -Dcms.schema.diff.out=<file>} the test writes the DDL of the
 * migration that would bring the schema to the declarations into the file and passes, so the CLI prints or writes it.
 */
class EntitySchemaDiffTest extends EmbeddedPostgresTest {

    /** Where {@code cms migration diff} wants the statements; unset in a build, where any drift fails. */
    static final String OUT = "cms.schema.diff.out";

    @Autowired
    private List<EntityDefinition> entities;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("6.1: every declared table and column exists in the migrated schema")
    void declarationsMatchTheSchema() throws IOException {
        List<String> statements = EntitySchemaDiff.statements(entities, new SchemaCatalog(jdbc));
        String out = System.getProperty(OUT);
        if (out != null && !out.isBlank()) {
            Files.writeString(Path.of(out), String.join("\n\n", statements) + "\n", StandardCharsets.UTF_8);
            return;
        }
        assertThat(statements)
                .as("what the schema lacks of the declarations; `cms migration diff` writes the migration")
                .isEmpty();
    }

    @Test
    @DisplayName("6.1: a missing table is created by the convention, a missing column is added nullable")
    void writesTheMissingTableAndColumns() {
        EntityDefinition orders = Entity.define("probe.orders", "probe.orders")
                .table("probe_orders", "o")
                .scope(EntityScope.all())
                .field(text("name", "probe.orders.col.name")
                        .column("name")
                        .required()
                        .length(1, 255))
                .field(select("state", "probe.orders.col.state", List.of("new", "done"), null)
                        .column("state"))
                .field(money("total", "probe.orders.col.total", "UZS").money("total_amount", "total_currency"))
                .field(ref("ownerId", "probe.orders.col.owner")
                        .column("owner_id")
                        .target("probe.owners", "name"))
                .section("main", "entity.section.main", "name", "state", "total", "ownerId")
                .actions("create")
                .archivable()
                .defaultSort("name", Entity.Sort.ASC)
                .build();
        EntityDefinition owners = Entity.define("probe.owners", "probe.owners")
                .table("probe_owners", "w")
                .scope(EntityScope.all())
                .field(text("name", "probe.owners.col.name").column("name").required())
                .field(date("since", "probe.owners.col.since").column("since").required())
                .section("main", "entity.section.main", "name", "since")
                .actions("create")
                .defaultSort("name", Entity.Sort.ASC)
                .build();
        Map<String, Set<String>> schema = Map.of("probe_owners", Set.of("id", "name"));
        EntitySchemaDiff.Catalog catalog = new EntitySchemaDiff.Catalog() {
            @Override
            public boolean hasTable(String table) {
                return schema.containsKey(table);
            }

            @Override
            public boolean hasColumn(String table, String column) {
                return schema.getOrDefault(table, Set.of()).contains(column);
            }
        };

        List<String> statements = EntitySchemaDiff.statements(List.of(owners, orders), catalog);

        assertThat(String.join("\n", statements))
                .contains("create table probe_orders (")
                .contains("name text not null constraint probe_orders_ck_name check (char_length(name) <= 255)")
                .contains("state text constraint probe_orders_ck_state check (state in ('new', 'done'))")
                .contains("total_amount numeric(19, 4)")
                .contains("owner_id bigint constraint probe_orders_fk_owner references probe_owners (id)")
                .contains("archived_by bigint constraint probe_orders_fk_archived_by references md_users (id)")
                .contains("constraint probe_orders_ck_attributes check (jsonb_typeof(attributes) = 'object')")
                .contains("create index probe_orders_owner_id_idx on probe_orders (owner_id);")
                .contains("alter table probe_owners add column since date;")
                .doesNotContain("add column name");
    }

    /** The current schema of the test database. */
    private record SchemaCatalog(JdbcClient jdbc) implements EntitySchemaDiff.Catalog {
        @Override
        public boolean hasTable(String table) {
            return jdbc.sql("select to_regclass(:table) is not null")
                    .param("table", table)
                    .query(Boolean.class)
                    .single();
        }

        @Override
        public boolean hasColumn(String table, String column) {
            return jdbc.sql("""
                            select exists (select 1 from information_schema.columns
                            where table_schema = current_schema() and table_name = :table and column_name = :column)
                            """)
                    .param("table", table)
                    .param("column", column)
                    .query(Boolean.class)
                    .single();
        }
    }
}
