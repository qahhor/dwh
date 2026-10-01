package com.smartup24.cms.instance.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.security.DataScopeRules;
import com.smartup24.cms.instance.common.security.RoleMembershipAuthorizer;
import com.smartup24.cms.instance.search.service.SearchAccessPolicy;

/** The search access policy of the tests that are not about access: every caller's data scope is unrestricted. */
final class SearchAccessFixtures {

    private SearchAccessFixtures() {}

    /** A data-scope reader that answers the rule ALL for everyone (ADR-0013, 2.5). */
    static DataScopeRules unrestrictedScope() {
        DataScopeRules rules = mock(DataScopeRules.class);
        when(rules.isUnrestricted(any())).thenReturn(true);
        return rules;
    }

    /** The policy with the given role reader and an unrestricted data scope. */
    static SearchAccessPolicy policy(RoleMembershipAuthorizer roles) {
        return new SearchAccessPolicy(roles, unrestrictedScope());
    }

    /** The policy for a legacy {@code *.*} caller: no role lookup matters. */
    static SearchAccessPolicy policy() {
        return policy(mock(RoleMembershipAuthorizer.class));
    }
}
