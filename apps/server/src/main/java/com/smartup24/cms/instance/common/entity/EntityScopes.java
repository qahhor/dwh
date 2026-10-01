package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.DataScopes;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns an entity's declared {@link EntityScope} into the predicate of its queries (ADR-0032, 5.1; ADR-0013, 2.4):
 * the list, its count and its export take {@link #listPredicate}, a read, change, delete, action or history by id takes
 * {@link #rows} — so a record outside the scope is never selected and answers as a missing one (404, not 403). The
 * org-unit rule comes from {@link DataScopes}, which the md module implements; {@code common} knows no module.
 */
@Component
public class EntityScopes {

    /** The parameter of the viewer in a scope fragment; registry parameters start with {@code q_}. */
    public static final String USER_PARAM = "scopeUserId";

    /** The field error of a record put in a unit outside its author's scope. */
    public static final String OUT_OF_SCOPE = "out_of_scope";

    /** No org-unit rule: what a service built by hand, without the md module, has. */
    private static final DataScopes NO_ORG_UNITS = new DataScopes() {
        @Override
        public ScopeFilter filterFor(long userId, String orgUnitColumn, String ownerColumn) {
            throw new IllegalStateException("An org-unit scope needs the data scope of the md module");
        }

        @Override
        public boolean unitVisible(long userId, long orgUnitId) {
            throw new IllegalStateException("An org-unit scope needs the data scope of the md module");
        }

        @Override
        public Optional<Long> homeUnit(long userId) {
            return Optional.empty();
        }
    };

    private final DataScopes dataScopes;

    public EntityScopes(DataScopes dataScopes) {
        this.dataScopes = Objects.requireNonNull(dataScopes, "dataScopes");
    }

    /** The owner, {@code all} and custom scopes alone, for a service built by hand; an org-unit scope fails loudly. */
    public static EntityScopes withoutOrgUnits() {
        return new EntityScopes(NO_ORG_UNITS);
    }

    /** The rows of the entity the user sees, as a filter over its alias. */
    public ScopeFilter filter(EntityDefinition entity, long userId) {
        EntityModel model = modelOf(entity);
        String alias = model.alias();
        return switch (model.scope()) {
            case EntityScope.Owner owner -> ScopeFilter.byOwner(alias + "." + owner.ownerColumn(), userId);
            case EntityScope.OrgUnit unit ->
                dataScopes.filterFor(userId, alias + "." + unit.orgUnitColumn(), alias + "." + unit.ownerColumn());
            case EntityScope.All _ -> ScopeFilter.unrestricted();
            case EntityScope.Custom custom -> custom.provider().filter(userId, alias);
        };
    }

    /** The scope predicate of a query by id: {@code ""} or {@code " and ..."} with its parameter. */
    public QueryPlan.SqlFragment rows(EntityDefinition entity, long userId) {
        return fragment(filter(entity, userId));
    }

    /**
     * The predicate of the entity's list, its count and its export: the scope, and — for an archivable entity — only
     * the records in use, unless the plan filters on the {@code archived} field (ADR-0032, 5.4).
     */
    public QueryPlan.SqlFragment listPredicate(EntityDefinition entity, QueryPlan plan, long userId) {
        QueryPlan.SqlFragment scope = rows(entity, userId);
        if (!entity.capabilities().contains(EntityCapability.ARCHIVE) || filtersArchived(plan)) return scope;
        return new QueryPlan.SqlFragment(
                scope.sql() + " and " + modelOf(entity).alias() + ".archived_at is null", scope.params());
    }

    /**
     * Refuses a record put in a unit outside its author's scope (ADR-0032, 5.1): 422 with {@code out_of_scope} on the
     * field that holds the unit. Only an org-unit entity has a unit to check.
     */
    public void requireUnit(EntityDefinition entity, String fieldKey, long userId, long orgUnitId) {
        Optional<FieldErrorItem> problem = unitProblem(entity, fieldKey, userId, orgUnitId);
        if (problem.isPresent()) {
            throw ApiException.validation("error.common.record_fields_invalid", List.of(problem.get()));
        }
    }

    /** The problem {@link #requireUnit} refuses, or none: the runtime answers it with the other problems of a save. */
    public Optional<FieldErrorItem> unitProblem(EntityDefinition entity, String fieldKey, long userId, long orgUnitId) {
        if (!(modelOf(entity).scope() instanceof EntityScope.OrgUnit) || dataScopes.unitVisible(userId, orgUnitId)) {
            return Optional.empty();
        }
        return Optional.of(FieldErrorItem.keyed(fieldKey, OUT_OF_SCOPE, "error.field.out_of_scope"));
    }

    /** A filter as a fragment of a query that ends in {@code where 1=1}. */
    public static QueryPlan.SqlFragment fragment(ScopeFilter filter) {
        if (filter.isUnrestricted()) return new QueryPlan.SqlFragment("", Map.of());
        return new QueryPlan.SqlFragment(
                filter.sql(),
                filter.bindsUserId() ? Map.of(USER_PARAM, Objects.requireNonNull(filter.userId())) : Map.of());
    }

    private static boolean filtersArchived(QueryPlan plan) {
        return plan.conditions().stream()
                .anyMatch(condition ->
                        EntityModel.ARCHIVED.equals(condition.field().key()));
    }

    private static EntityModel modelOf(EntityDefinition entity) {
        EntityModel model = entity.model();
        if (model == null) {
            throw new IllegalArgumentException("Entity " + entity.code() + " has no table to restrict");
        }
        return model;
    }
}
