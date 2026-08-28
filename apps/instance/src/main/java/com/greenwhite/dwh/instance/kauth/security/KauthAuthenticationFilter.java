package com.greenwhite.dwh.instance.kauth.security;

import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.kauth.pref.KauthPref;
import com.greenwhite.dwh.instance.kauth.service.KauthApiTokenService;
import com.greenwhite.dwh.instance.kauth.service.KauthSessionService;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class KauthAuthenticationFilter extends OncePerRequestFilter {

    private final KauthSessionService sessionService;
    private final KauthApiTokenService apiTokenService;
    private final MdUserService userService;
    private final MdPermissionService permissionService;

    public KauthAuthenticationFilter(
            KauthSessionService sessionService,
            KauthApiTokenService apiTokenService,
            MdUserService userService,
            MdPermissionService permissionService) {
        this.sessionService = sessionService;
        this.apiTokenService = apiTokenService;
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // 1. Try Bearer API Token
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String rawToken = authHeader.substring(7).trim();
                var tokenOpt = apiTokenService.validateToken(rawToken);
                if (tokenOpt.isPresent()) {
                    var token = tokenOpt.get();
                    try {
                        var user = userService.getUserById(token.userId());
                        if (MdPref.STATE_ACTIVE.equals(user.state())) {
                            apiTokenService.recordTokenUsage(token.id());
                            Set<String> permissions = permissionService.getEffectivePermissions(user.id());
                            long version = permissionService.getPermissionVersion(user.id());
                            SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                                    user.id(), user.login(), user.email(), null, true, permissions, version
                            ));
                        }
                    } catch (Exception ignored) {}
                }
            }

            // 2. Try Cookie Session
            if (!SecurityContext.isAuthenticated()) {
                String sessionCookieValue = extractSessionCookie(request);
                if (sessionCookieValue != null) {
                    var sessionOpt = sessionService.getActiveSession(sessionCookieValue);
                    if (sessionOpt.isPresent()) {
                        var session = sessionOpt.get();
                        try {
                            var user = userService.getUserById(session.userId());
                            if (MdPref.STATE_ACTIVE.equals(user.state())) {
                                sessionService.updateLastSeen(session.id());
                                Set<String> permissions = permissionService.getEffectivePermissions(user.id());
                                long version = permissionService.getPermissionVersion(user.id());
                                SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                                        user.id(), user.login(), user.email(), session.id(), false, permissions, version
                                ));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            SecurityContext.clear();
        }
    }

    private String extractSessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (KauthPref.SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
