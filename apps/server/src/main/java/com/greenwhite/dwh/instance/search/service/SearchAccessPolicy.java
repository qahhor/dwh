package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.RoleMembershipAuthorizer;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.search.pref.SearchPref;
import org.springframework.stereotype.Component;

/** Enforces the current permission and unrestricted legacy/admin scope for global search. */
@Component
public class SearchAccessPolicy {
    private static final String ADMINISTRATOR_ROLE = "admin";
    private final RoleMembershipAuthorizer roleMembershipAuthorizer;

    public SearchAccessPolicy(RoleMembershipAuthorizer roleMembershipAuthorizer) {
        this.roleMembershipAuthorizer = roleMembershipAuthorizer;
    }

    public void requireSearchAccess() {
        var principal = SecurityContext.getPrincipal();
        if (principal == null) throw ApiException.unauthorized("Требуется авторизация для поиска");
        if (!SecurityContext.hasPermission(SearchPref.FORM_SEARCH, "view")) {
            throw ApiException.permissionDenied(SearchPref.FORM_SEARCH, "view");
        }
        boolean hasLegacyWildcard = principal.effectivePermissions().contains("*.*");
        boolean hasAdministratorRole = !hasLegacyWildcard
                && roleMembershipAuthorizer.hasActiveRole(principal.userId(), ADMINISTRATOR_ROLE);
        if (!hasLegacyWildcard && !hasAdministratorRole) {
            throw ApiException.forbidden(
                    "Глобальный поиск доступен только администраторам до внедрения scope-фильтрации");
        }
    }

    public void requireSettingsRead() {
        requireSearchAccess();
        if (!SecurityContext.hasPermission("platform.settings", "view"))
            throw ApiException.permissionDenied("platform.settings", "view");
    }

    public void requireSettingsUpdate() {
        requireSearchAccess();
        if (!SecurityContext.hasPermission("platform.settings", "update"))
            throw ApiException.permissionDenied("platform.settings", "update");
    }
}
