package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.date;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.money;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 6.1: the entry point of {@code cms migration diff} (tools/cms-cli). The comparison is
 * {@link EntitySchemaCheck} (ADR-0033, 7), the one the start runs and {@code EntitySchemaContractTest} proves in the
 * build; {@link EntitySchemaDiff} only writes the DDL for what the check's schema snapshot lacks. With
 * {@code -Dcms.schema.diff.out=<file>} the test writes that DDL into the file and every difference the check reports
 * into {@code <file>.problems}, and passes, so the CLI prints or writes them.
 */
class EntitySchemaDiffTest extends EmbeddedPostgresTest {

    /** Where {@code cms migration diff} wants the statements; unset in a build. */
    static final String OUT = "cms.schema.diff.out";

    @Autowired
    private List<EntityDefinition> entities;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("6.1: the migrated schema leaves the DDL writer nothing to write")
    void declarationsMatchTheSchema() throws IOException {
        EntitySchemaCheck schema = EntitySchemaCheck.of(jdbc);
        List<String> problems = schema.problems(entities);
        List<String> statements = EntitySchemaDiff.statements(entities, schema);
        String out = System.getProperty(OUT);
        if (out != null && !out.isBlank()) {
            Files.writeString(Path.of(out), String.join("\n\n", statements) + "\n", StandardCharsets.UTF_8);
            Files.writeString(Path.of(out + ".problems"), String.join("\n", problems) + "\n", StandardCharsets.UTF_8);
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
        EntitySchemaCheck schema = new EntitySchemaCheck(
                Map.of(
                        "probe_owners",
                        Map.of(
                                "id", new EntitySchemaCheck.Column("bigint", false, true),
                                "name", new EntitySchemaCheck.Column("text", false, false))),
                Map.of());

        List<String> statements = EntitySchemaDiff.statements(List.of(owners, orders), schema);

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
        assertThat(schema.problems(List.of(owners))).contains("probe.owners: probe_owners.since is missing (field since"
                + " (date))");
    }
}
