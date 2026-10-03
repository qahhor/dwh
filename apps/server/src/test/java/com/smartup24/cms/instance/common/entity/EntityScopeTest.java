package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.DataScopes;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 5.3 (ADR-0032, 5.1): an entity with a table declares its scope or is not built, and the declared
 * scope is the predicate of its list and of every read by id — the owner's rows, the org-unit rule of the md module,
 * no restriction, or the module's own rule; an archivable entity's list leaves archived records out until asked.
 */
class EntityScopeTest {

    /** The org-unit rule of a viewer with the rule UNITS: units 1 and 2 are theirs. */
    static final class UnitsRule implements DataScopes {
        final List<String> asked = new ArrayList<>();

        @Override
        public ScopeFilter filterFor(long userId, String orgUnitColumn, String ownerColumn) {
            asked.add(orgUnitColumn + "|" + ownerColumn);
            return ScopeFilter.byOrgUnit(orgUnitColumn, userId);
        }

        @Override
        public boolean unitVisible(long userId, long orgUnitId) {
            return orgUnitId == 1 || orgUnitId == 2;
        }

        @Override
        public Optional<Long> homeUnit(long userId) {
            return Optional.of(1L);
        }
    }

    private static Entity items() {
        return Entity.define("x.items", "x")
                .table("x_items", "i")
                .field(text("name", "x.col.name").column("name").list(sortable()))
                .section("main", "m", "name")
                .defaultSort("name", Entity.Sort.ASC);
    }

    @Test
    void anEntityWithATableIsNotBuiltWithoutItsScope() {
        assertThatThrownBy(() -> items().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x.items")
                .hasMessageContaining(".scope(");
    }

    @Test
    void aScopeRestrictsTheRowsOfATableOnly() {
        assertThatThrownBy(() ->
                        Entity.define("x.form", "x").scope(EntityScope.all()).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table");
    }

    @Test
    void scopeColumnsAreIdentifiers() {
        assertThatThrownBy(() -> EntityScope.owner("created_by; drop table x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityScope.orgUnit("org_unit_id", "Owner"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityScope.custom("Bad name", (user, alias) -> EntityScope.Condition.unrestricted()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EntityScope.orgUnit("org_unit_id", "created_by").columns())
                .containsExactly("org_unit_id", "created_by");
        assertThat(EntityScope.all().columns()).isEmpty();
    }

    @Test
    void theOwnerSeesOnlyTheirRows() {
        EntityDefinition entity = items().scope(EntityScope.owner("created_by")).build();
        QueryPlan.SqlFragment rows = EntityScopes.withoutOrgUnits().rows(entity, 7L);

        assertThat(rows.sql()).isEqualTo(" and i.created_by = :scopeUserId");
        assertThat(rows.params()).containsExactly(Map.entry(EntityScopes.USER_PARAM, 7L));
        assertThat(entity.model().scope().describe()).isEqualTo("owner(created_by)");
    }

    @Test
    void anOrgUnitRecordFollowsTheRuleOfTheMdModule() {
        EntityDefinition entity =
                items().scope(EntityScope.orgUnit("org_unit_id", "created_by")).build();
        UnitsRule rule = new UnitsRule();
        EntityScopes scopes = new EntityScopes(rule);

        QueryPlan.SqlFragment rows = scopes.rows(entity, 7L);

        assertThat(rule.asked).containsExactly("i.org_unit_id|i.created_by");
        assertThat(rows.sql()).contains("i.org_unit_id in (").contains("md_effective_scope");
        assertThat(rows.params()).containsEntry(EntityScopes.USER_PARAM, 7L);
        // A record put in a unit outside the author's scope: 422 on the field that holds the unit.
        scopes.requireUnit(entity, "orgUnitId", 7L, 2L);
        assertThatThrownBy(() -> scopes.requireUnit(entity, "orgUnitId", 7L, 9L))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                                .containsExactly(tuple("orgUnitId", EntityScopes.OUT_OF_SCOPE)));
        // Without the md module an org-unit scope fails loudly instead of showing every row.
        assertThatThrownBy(() -> EntityScopes.withoutOrgUnits().rows(entity, 7L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void referenceDataIsNotRestrictedAndACustomRuleIsTheModules() {
        EntityDefinition all = items().scope(EntityScope.all()).build();
        assertThat(EntityScopes.withoutOrgUnits().rows(all, 7L).sql()).isEmpty();
        assertThat(EntityScopes.withoutOrgUnits().rows(all, 7L).params()).isEmpty();
        // The unit rule needs no unit check for an entity that has none.
        new EntityScopes(new UnitsRule()).requireUnit(all, "orgUnitId", 7L, 9L);

        EntityDefinition custom = items().scope(EntityScope.custom(
                        "participation",
                        (user, alias) -> ScopeFilter.byOwner(alias + ".reporter_id", user)
                                .condition()))
                .build();
        QueryPlan.SqlFragment rows = EntityScopes.withoutOrgUnits().rows(custom, 5L);
        assertThat(rows.sql()).isEqualTo(" and i.reporter_id = :scopeUserId");
        assertThat(rows.params()).containsEntry(EntityScopes.USER_PARAM, 5L);
        assertThat(custom.model().scope().describe()).isEqualTo("custom(participation)");
    }

    @Test
    void anArchivableListLeavesArchivedRecordsOutUntilTheFilterAsksForThem() {
        EntityDefinition entity =
                items().scope(EntityScope.owner("created_by")).archivable().build();
        EntityScopes scopes = EntityScopes.withoutOrgUnits();
        var list = EntityLists.queryList(entity);

        QueryPlan plain = QueryCompiler.compile(list, null, null, null, null, null);
        assertThat(scopes.listPredicate(entity, plain, 7L).sql())
                .isEqualTo(" and i.created_by = :scopeUserId and i.archived_at is null");

        QueryPlan archive = QueryCompiler.compile(
                list, "[{\"field\":\"archived\",\"op\":\"eq\",\"value\":true}]", null, null, null, null);
        assertThat(scopes.listPredicate(entity, archive, 7L).sql()).isEqualTo(" and i.created_by = :scopeUserId");
        assertThat(archive.where().sql()).contains("(i.archived_at is not null)");

        // The archived flag is a hidden, filterable list field; the record reads when it was archived.
        assertThat(list.field(EntityModel.ARCHIVED)).hasValueSatisfying(field -> {
            assertThat(field.filterable()).isTrue();
            assertThat(field.defaultVisible()).isFalse();
            assertThat(field.labelKey()).isEqualTo(EntityLists.ARCHIVED_LABEL);
        });
        assertThat(list.select()).contains("i.archived_at as \"archivedAt\"");

        // Without the capability nothing is added.
        EntityDefinition plainEntity =
                items().scope(EntityScope.owner("created_by")).build();
        assertThat(scopes.listPredicate(plainEntity, plain, 7L).sql()).isEqualTo(" and i.created_by = :scopeUserId");
        assertThat(EntityLists.queryList(plainEntity).field(EntityModel.ARCHIVED))
                .isEmpty();
    }

    @Test
    void theArchiveCapabilityGoesWithItsActionAndATable() {
        EntityDefinition entity =
                items().scope(EntityScope.owner("created_by")).archivable().build();
        assertThat(entity.action(EntityDefinition.ARCHIVE))
                .hasValueSatisfying(action -> assertThat(action.permission()).isEqualTo(EntityDefinition.DELETE));
        assertThat(items().scope(EntityScope.all())
                        .archivable("archive")
                        .build()
                        .action("archive"))
                .hasValueSatisfying(action -> assertThat(action.permission()).isEqualTo("archive"));
        assertThatThrownBy(() -> items().scope(EntityScope.all())
                        .capabilities(EntityCapability.ARCHIVE)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archive action");
        assertThatThrownBy(() -> Entity.define("x.form", "x").archivable().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table");
        // The record's own keys stay its own.
        assertThatThrownBy(() -> Entity.define("x.items", "x")
                        .table("x_items", "i")
                        .scope(EntityScope.all())
                        .field(text("archived", "x.col.a").column("archived"))
                        .section("main", "m", "archived")
                        .defaultSort("archived", Entity.Sort.ASC)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record's own");
    }
}
