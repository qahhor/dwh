package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.DataScopeRules;
import com.smartup24.cms.instance.common.security.DataScopeRules.Viewer;
import com.smartup24.cms.instance.search.repository.SearchDocumentSql;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The caller's scope in a search (ADR-0032, 10.3; ADR-0013, 2.5): the filter of the index query over the scope keys of
 * the documents, and the predicate the found records are checked again with in the database — the entity's own scope,
 * the one its list and every read by id apply (ADR-0032, 5.1). The index is never the authority: a record the database
 * check drops is not answered, so a stale document leaks nothing.
 *
 * <ul>
 *   <li>a personal record ({@code owner}) — its owner only, whatever the caller's rule;
 *   <li>reference data ({@code all}) — no filter;
 *   <li>otherwise by the caller's rule: {@code ALL} — no filter, {@code SELF} — the documents whose users hold the caller,
 *       {@code SUBTREE}/{@code UNITS} — the documents whose units meet the caller's scope.
 * </ul>
 */
@Component
public class SearchScopes {

    /** More units than this are not listed in the filter: the database check alone restricts the candidates then. */
    static final int MAX_FILTER_UNITS = 500;

    private final EntityScopes scopes;
    private final DataScopeRules rules;

    public SearchScopes(EntityScopes scopes, DataScopeRules rules) {
        this.scopes = scopes;
        this.rules = rules;
    }

    /** The signed-in caller with their data scope. */
    public Caller caller(long userId) {
        return new Caller(userId, rules.viewer(userId));
    }

    /** The filter of the entity's index query for the caller. */
    public IndexFilter indexFilter(SearchEntity entity, Caller caller) {
        String self = SearchDocumentSql.SCOPE_USERS + ":=" + caller.userId();
        return switch (entity.model().scope()) {
            case EntityScope.Owner _ -> IndexFilter.by(self);
            case EntityScope.All _ -> IndexFilter.UNRESTRICTED;
            case EntityScope.OrgUnit _, EntityScope.Custom _ -> byRule(caller.viewer(), self);
        };
    }

    /** The scope predicate of the entity's records for the caller, as its runtime reads them by id. */
    public QueryPlan.SqlFragment rows(SearchEntity entity, Caller caller) {
        return scopes.rows(entity.definition(), caller.userId());
    }

    private static IndexFilter byRule(Viewer viewer, String self) {
        if (viewer.unrestricted()) return IndexFilter.UNRESTRICTED;
        if (viewer.self()) return IndexFilter.by(self);
        if (viewer.units().isEmpty()) return IndexFilter.NOTHING;
        if (viewer.units().size() > MAX_FILTER_UNITS) return IndexFilter.UNRESTRICTED;
        return IndexFilter.by(SearchDocumentSql.SCOPE_UNITS + ":["
                + viewer.units().stream().map(String::valueOf).collect(Collectors.joining(",")) + "]");
    }

    /** A signed-in caller and their data scope (ADR-0013). */
    public record Caller(long userId, Viewer viewer) {}

    /**
     * The scope part of an index query.
     *
     * @param nothing  the caller sees no record of the entity: no query is sent
     * @param filterBy the Typesense filter, or null when the index filters nothing
     */
    public record IndexFilter(boolean nothing, @Nullable String filterBy) {

        static final IndexFilter UNRESTRICTED = new IndexFilter(false, null);
        static final IndexFilter NOTHING = new IndexFilter(true, null);

        static IndexFilter by(String filter) {
            return new IndexFilter(false, filter);
        }
    }
}
