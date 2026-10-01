package com.smartup24.cms.instance.kauth.security;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.service.KauthApiTokenService;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication by a Bearer API token or a cookie session.
 * Takes part ONLY in the Spring Security chain (see SecurityConfig:
 * auto-registration in the servlet container is disabled). Fills both contexts:
 * our thread-local SecurityContext (RBAC interceptor) and SecurityContextHolder
 * (Spring Security authorization).
 */
@Component
public class KauthAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(KauthAuthenticationFilter.class);

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
                    authenticateUser(
                            token.userId(),
                            token.authenticationVersion(),
                            () -> apiTokenService.recordTokenUsage(token.id()),
                            null,
                            true,
                            token.id());
                }
            }

            // 2. Try Cookie Session
            if (!SecurityContext.isAuthenticated()) {
                String sessionCookieValue = extractSessionCookie(request);
                if (sessionCookieValue != null) {
                    var sessionOpt = sessionService.getActiveSession(sessionCookieValue);
                    if (sessionOpt.isPresent()) {
                        var session = sessionOpt.get();
                        authenticateUser(
                                session.userId(),
                                session.authenticationVersion(),
                                () -> sessionService.updateLastSeen(session.id()),
                                session.id(),
                                false,
                                null);
                    }
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            // SecurityContextHolder is cleared by the chain's own SecurityContextHolderFilter;
            // clearing it by hand here would wipe the authentication BEFORE
            // ExceptionTranslationFilter (higher up the chain) handles the exception.
            SecurityContext.clear();
        }
    }

    /**
     * Authenticates the owner of a valid token or session while the account is active and the credential belongs
     * to its current authentication version. {@code touch} records the use (token usage or session last-seen).
     * A database failure propagates; any other failure leaves the request anonymous.
     */
    private void authenticateUser(
            Long userId, long authenticationVersion, Runnable touch, Long sessionId, boolean api, Long apiTokenId) {
        try {
            var user = userService.getUserIdentity(userId);
            if (MdPref.STATE_ACTIVE.equals(user.state()) && user.authenticationVersion() == authenticationVersion) {
                touch.run();
                Set<String> permissions = permissionService.getEffectivePermissions(user.id());
                long version = permissionService.getPermissionVersion(user.id());
                authenticate(new SecurityContext.KauthPrincipal(
                        user.id(),
                        user.login(),
                        user.email(),
                        sessionId,
                        api,
                        permissions,
                        version,
                        user.forcePasswordChange(),
                        authenticationVersion,
                        apiTokenId));
            }
        } catch (DataAccessException e) {
            throw e;
        } catch (ApiException gone) {
            // The credential names a user who no longer exists: the request stays anonymous.
            log.debug("kauth_credential_user_missing userId={} code={}", userId, gone.getErrorCode());
        } catch (RuntimeException unexpected) {
            // Any other failure is a defect: the request stays anonymous, and the log says why.
            log.warn("kauth_authentication_failed userId={}", userId, unexpected);
        }
    }

    private void authenticate(SecurityContext.KauthPrincipal principal) {
        SecurityContext.setPrincipal(principal);
        var authority = new SimpleGrantedAuthority(principal.isApi() ? "ROLE_API" : "ROLE_USER");
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(authority));
        SecurityContextHolder.getContext().setAuthentication(authentication);
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
