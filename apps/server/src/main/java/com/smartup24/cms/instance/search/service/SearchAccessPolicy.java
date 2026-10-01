package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.DataScopeRules;
import com.smartup24.cms.instance.common.security.RoleMembershipAuthorizer;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.pref.SearchPref;
import org.springframework.stereotype.Component;

/**
 * Who may use global search (ADR-0013, 2.5). The index has no row scope yet, so the results are fail-closed: only an
 * unrestricted administrator reads them, that is an administrator (the active role {@code admin}, or the legacy
 * {@code *.*} grant) whose effective data-scope rule is {@code ALL}. An administrator with a narrower rule would see
 * rows their own lists hide, and a non-administrator with {@code ALL} would see entities their permissions hide (the
 * index does not check per-entity view permissions), so both are refused. Managing the index (status, jobs,
 * settings) reveals no rows and stays with any administrator. Notes in the results are their owner's alone.
 */
@Component
public class SearchAccessPolicy {
    private static final String ADMINISTRATOR_ROLE = "admin";
    private final RoleMembershipAuthorizer roleMembershipAuthorizer;
    private final DataScopeRules dataScopeRules;

    public SearchAccessPolicy(RoleMembershipAuthorizer roleMembershipAuthorizer, DataScopeRules dataScopeRules) {
        this.roleMembershipAuthorizer = roleMembershipAuthorizer;
        this.dataScopeRules = dataScopeRules;
    }

    /** The search results and the preview: an administrator whose data scope is unrestricted. */
    public void requireSearchAccess() {
        Long userId = requireAdministrator();
        if (!dataScopeRules.isUnrestricted(userId)) {
            throw ApiException.forbidden("error.search.scope_restricted");
        }
    }

    /**
     * The index itself (status, jobs, settings): an administrator with the search permission, whatever their data
     * scope; it answers counts and configuration, never rows. Returns the caller's id.
     */
    public Long requireAdministrator() {
        var principal = SecurityContext.getPrincipal();
        if (principal == null) throw ApiException.unauthorized("error.search.auth_required");
        if (!SecurityContext.hasPermission(SearchPref.FORM_SEARCH, "view")) {
            throw ApiException.permissionDenied(SearchPref.FORM_SEARCH, "view");
        }
        boolean hasLegacyWildcard = principal.effectivePermissions().contains("*.*");
        boolean hasAdministratorRole =
                !hasLegacyWildcard && roleMembershipAuthorizer.hasActiveRole(principal.userId(), ADMINISTRATOR_ROLE);
        if (!hasLegacyWildcard && !hasAdministratorRole) {
            throw ApiException.forbidden("error.search.admin_only");
        }
        return principal.userId();
    }

    public void requireSettingsRead() {
        requireAdministrator();
        if (!SecurityContext.hasPermission("md.settings", "view"))
            throw ApiException.permissionDenied("md.settings", "view");
    }

    public void requireSettingsUpdate() {
        requireAdministrator();
        if (!SecurityContext.hasPermission("md.settings", "update"))
            throw ApiException.permissionDenied("md.settings", "update");
    }
}
