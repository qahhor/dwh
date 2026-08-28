package com.greenwhite.dwh.instance.common.security;

import java.util.Set;

public final class SecurityContext {

    private static final ThreadLocal<KauthPrincipal> CURRENT_PRINCIPAL = new ThreadLocal<>();

    private SecurityContext() {}

    public static void setPrincipal(KauthPrincipal principal) {
        CURRENT_PRINCIPAL.set(principal);
    }

    public static KauthPrincipal getPrincipal() {
        return CURRENT_PRINCIPAL.get();
    }

    public static boolean isAuthenticated() {
        return CURRENT_PRINCIPAL.get() != null;
    }

    public static Long getCurrentUserId() {
        var p = CURRENT_PRINCIPAL.get();
        return p != null ? p.userId() : null;
    }

    public static boolean hasPermission(String form, String action) {
        var p = CURRENT_PRINCIPAL.get();
        if (p == null) return false;
        if (p.effectivePermissions().contains("*.*")) return true;
        return p.effectivePermissions().contains(form + "." + action);
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
            Set<String> effectivePermissions,
            long permissionVersion
    ) {}
}
