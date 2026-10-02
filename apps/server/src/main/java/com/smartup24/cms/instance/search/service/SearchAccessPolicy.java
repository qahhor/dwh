package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.RoleMembershipAuthorizer;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.pref.SearchPref;
import org.springframework.stereotype.Component;

/**
 * Who may use global search (ADR-0013, 2.5; ADR-0032, 10.3 and 19, question 8). The search answers the records of the
 * entities with the SEARCH capability to anyone with the search permission: the documents carry the scope keys of their
 * records, the index query filters them by the caller's scope, and every hit is checked again in the database with the
 * entity's own scope and its {@code view} right — so nobody, administrator or not, finds a record their own lists
 * hide; a personal record (a note) is its owner's alone. Managing the index (status, jobs, settings, the preview of
 * settings) reveals no rows outside the caller's scope and stays with an administrator.
 */
@Component
public class SearchAccessPolicy {
    private static final String ADMINISTRATOR_ROLE = "admin";
    private final RoleMembershipAuthorizer roleMembershipAuthorizer;

    public SearchAccessPolicy(RoleMembershipAuthorizer roleMembershipAuthorizer) {
        this.roleMembershipAuthorizer = roleMembershipAuthorizer;
    }

    /** The search results: a signed-in caller with the search permission. Returns the caller's id. */
    public Long requireSearchAccess() {
        var principal = SecurityContext.getPrincipal();
        if (principal == null) throw ApiException.unauthorized("error.search.auth_required");
        if (!SecurityContext.hasPermission(SearchPref.FORM_SEARCH, "view")) {
            throw ApiException.permissionDenied(SearchPref.FORM_SEARCH, "view");
        }
        return principal.userId();
    }

    /**
     * The index itself (status, jobs, settings, preview): an administrator with the search permission, whatever their
     * data scope; it answers counts and configuration, and a preview only the rows of the administrator's own scope.
     * Returns the caller's id.
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
