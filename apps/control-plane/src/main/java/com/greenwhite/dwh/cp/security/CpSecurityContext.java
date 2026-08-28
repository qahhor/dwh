package com.greenwhite.dwh.cp.security;

import java.util.Set;

/** Thread-local контекст текущего сотрудника control plane. */
public final class CpSecurityContext {

    private static final ThreadLocal<CpPrincipal> CURRENT = new ThreadLocal<>();

    private CpSecurityContext() {}

    public static void set(CpPrincipal p) { CURRENT.set(p); }
    public static CpPrincipal get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    public static Long currentUserId() {
        CpPrincipal p = CURRENT.get();
        return p != null ? p.userId() : null;
    }

    public static boolean isAuthenticated() { return CURRENT.get() != null; }

    public static boolean hasRole(String roleCode) {
        CpPrincipal p = CURRENT.get();
        return p != null && p.roles().contains(roleCode);
    }

    public record CpPrincipal(Long userId, String login, String name, Long sessionId, Set<String> roles) {}
}
