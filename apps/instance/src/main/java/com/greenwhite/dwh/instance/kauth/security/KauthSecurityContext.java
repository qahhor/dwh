package com.greenwhite.dwh.instance.kauth.security;

import java.util.Set;

/**
 * Thread-local security context holding the authenticated user principal.
 */
public final class KauthSecurityContext {

    private static final ThreadLocal<KauthPrincipal> CURRENT_PRINCIPAL = new ThreadLocal<>();

    private KauthSecurityContext() {}

    public static void setPrincipal(KauthPrincipal principal) {
        CURRENT_PRINCIPAL.set(principal);
    }

    public static KauthPrincipal getPrincipal() {
        return CURRENT_PRINCIPAL.get();
    }

    public static Long getCurrentUserId() {
        KauthPrincipal principal = CURRENT_PRINCIPAL.get();
        return principal != null ? principal.userId() : null;
    }

    public static boolean isAuthenticated() {
        return CURRENT_PRINCIPAL.get() != null;
    }

    public static boolean hasPermission(String form, String action) {
        KauthPrincipal principal = CURRENT_PRINCIPAL.get();
        if (principal == null) {
            return false;
        }
        // Superadmin wildcard check
        if (principal.permissions().contains("*.*") || principal.permissions().contains(form + ".*")) {
            return true;
        }
        return principal.permissions().contains(form + "." + action);
    }

    public static void clear() {
        CURRENT_PRINCIPAL.remove();
    }

    public record KauthPrincipal(
            Long userId,
            String login,
            String email,
            Long sessionId,
            boolean isApi,
            Set<String> permissions,
            long permissionsVersion
    ) {}
}
