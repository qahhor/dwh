package com.smartup24.cms.instance.architecture;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.entity.EntitySqlBoundaries;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR-0032, 6.11 and 12; ADR-0026: the SQL of every built-in declaration — its tables, the expressions and computed
 * fields of its records and rows, and what its custom scope answers a viewer — reads only the relations its module owns
 * ({@link ModuleBoundariesTest#ownerOf}) and other modules' published views; a custom scope may read the data-scope
 * relations of {@code md}, which owns the scope rules (ADR-0013). The kit runs the same rule on every entity by the
 * prefix of its table, so a module outside the monorepo is held to it too.
 */
class EntitySqlBoundariesTest extends EmbeddedPostgresTest {

    @Autowired
    private EntityRegistry entities;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WebApplicationContext wac;

    @Test
    @DisplayName("ADR-0026: every built-in declaration's SQL reads its module's relations and published views only")
    void everyDeclarationReadsItsModulesRelationsOnly() {
        TestUsers users = TestUsers.of(wac);
        List<Long> viewers =
                List.of(users.withRights(Map.of(), users.unit("sql-boundaries")).id());
        Set<String> relations = EntitySqlBoundaries.relations(jdbc);
        List<String> found = new ArrayList<>();
        int declarations = 0;
        for (EntityDefinition entity : entities.all()) {
            EntityModel model = entity.model();
            if (model == null) continue;
            declarations++;
            Optional<String> module = ModuleBoundariesTest.ownerOf(model.table());
            assertThat(module)
                    .as("%s: the module that owns %s", entity.code(), model.table())
                    .isPresent();
            found.addAll(EntitySqlBoundaries.violations(
                    entity, EntitySqlBoundaries.fragments(entity, viewers), relations, ownedBy(module.get())));
        }
        assertThat(declarations).isGreaterThan(5);
        assertThat(found)
                .as("read another module's data through its published view <owner>_pub_* (ADR-0026)")
                .isEmpty();
    }

    @Test
    @DisplayName("ADR-0026: the rule catches a foreign table in an expression, a computed field and a scope")
    void theRuleCatchesForeignTables() {
        Set<String> relations = Set.of("ex_probes", "ex_probe_marks", "md_users", "md_effective_scope", "ms_tasks");
        EntityScope scope = EntityScope.custom(
                "probes",
                (user, alias) -> EntityScope.Condition.of(
                        " and " + alias + ".created_by in (select user_id from md_effective_scope"
                                + " where user_id = :scopeUserId) and exists (select 1 from ms_tasks t)",
                        user));
        EntityDefinition probe = Entity.define("example.probes", "example.probes")
                .table("ex_probes", "p")
                .scope(scope)
                .field(text("title", "x.title").column("title").list(sortable()))
                .field(text("author", "x.author")
                        .expression("(select u.name from md_users u where u.id = p.created_by)")
                        .listOnly(sortable()))
                .field(text("authorName", "x.author")
                        .expression("(select u.name from md_pub_users u where u.id = p.created_by)")
                        .listOnly(sortable()))
                .field(number("marks", "x.marks")
                        .computed("(select count(*) from ex_probe_marks m where m.probe_id = p.id)")
                        .listOnly(sortable()))
                .field(text("year", "x.year")
                        .expression("extract(year from p.created_at)::text")
                        .listOnly(sortable()))
                .section("main", "entity.section.main", "title")
                .actions("create")
                .defaultSort("title", Entity.Sort.ASC)
                .build();

        List<String> found = EntitySqlBoundaries.violations(
                probe, EntitySqlBoundaries.fragments(probe, List.of(1L)), relations, ownedBy("example"));

        assertThat(found)
                .containsExactly(
                        "example.probes: field author reads md_users", "example.probes: scope probes reads ms_tasks");
        assertThat(EntitySqlBoundaries.violations(
                        probe,
                        EntitySqlBoundaries.fragments(probe, List.of(1L)),
                        relations,
                        EntitySqlBoundaries.tablePrefixOf(probe)))
                .as("by the prefix of the table, as the kit checks a module outside the monorepo")
                .containsExactlyElementsOf(found);
        assertThat(EntitySqlBoundaries.named("extract(year from p.created_at) from md_users u join ex_a.b"))
                .containsExactly("md_users");
    }

    private static Predicate<String> ownedBy(String module) {
        return relation -> Objects.equals(ModuleBoundariesTest.ownerOf(relation).orElse(null), module);
    }
}
