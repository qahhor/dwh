package com.smartup24.cms.instance.search;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.security.DataScopeRules;
import com.smartup24.cms.instance.common.security.DataScopes;
import com.smartup24.cms.instance.common.security.RoleMembershipAuthorizer;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.service.SearchAccessPolicy;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.service.SearchExecutionSnapshotReader;
import com.smartup24.cms.instance.search.service.SearchFieldPolicies;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchResultBudget;
import com.smartup24.cms.instance.search.service.SearchScopes;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The search wired by hand for the tests that are not about access or scope: every caller's data scope is
 * unrestricted (the rule {@code ALL}, ADR-0013, 2.5), and an org-unit entity restricts nothing.
 */
final class SearchAccessFixtures {

    private SearchAccessFixtures() {}

    /** A data-scope reader that answers the rule ALL for everyone (ADR-0013, 2.5). */
    static DataScopeRules unrestrictedScope() {
        DataScopeRules rules = mock(DataScopeRules.class);
        when(rules.viewer(anyLong())).thenReturn(new DataScopeRules.Viewer(DataScopeRules.RULE_ALL, List.of()));
        return rules;
    }

    /** The org-unit rule of an unrestricted caller. */
    static DataScopes unrestrictedUnits() {
        DataScopes units = mock(DataScopes.class);
        when(units.filterFor(
                        anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(ScopeFilter.unrestricted());
        when(units.unitVisible(anyLong(), anyLong())).thenReturn(true);
        when(units.homeUnit(anyLong())).thenReturn(Optional.empty());
        return units;
    }

    /** The scope of an unrestricted caller. */
    static SearchScopes unrestrictedScopes() {
        return new SearchScopes(new EntityScopes(unrestrictedUnits()), unrestrictedScope());
    }

    /** The policy with the given role reader. */
    static SearchAccessPolicy policy(RoleMembershipAuthorizer roles) {
        return new SearchAccessPolicy(roles);
    }

    /** The policy for a legacy {@code *.*} caller: no role lookup matters. */
    static SearchAccessPolicy policy() {
        return policy(mock(RoleMembershipAuthorizer.class));
    }

    /** The search over the given engine, database and index state, for an unrestricted caller. */
    static SearchService service(
            TypesenseSearch typesense,
            JdbcClient jdbc,
            SearchPolicyProvider policies,
            SearchExecutionSnapshotReader snapshots,
            SearchEntities entities) {
        return new SearchService(
                typesense,
                new SearchFallbackRepository(jdbc),
                policy(),
                new SearchResultBudget(),
                policies,
                snapshots,
                entities,
                unrestrictedScopes(),
                new SearchFieldPolicies(entities),
                Optional.empty(),
                Optional.empty());
    }
}
