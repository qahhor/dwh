package com.smartup24.cms.instance.kauth.security;

import com.smartup24.cms.instance.common.security.ClientIpResolver;
import com.smartup24.cms.instance.kauth.api.KauthPref;
import com.smartup24.cms.instance.kauth.pref.KauthSessionProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * The session cookie {@link KauthPref#SESSION_COOKIE_NAME} in one place (plan 10/10, item 4.7): read, set and
 * removed with the same attributes.
 */
public class KauthSessionCookies {

    private final ClientIpResolver clientIpResolver;
    private final long maxAgeSeconds;

    public KauthSessionCookies(ClientIpResolver clientIpResolver) {
        this(clientIpResolver, KauthSessionProperties.defaults());
    }

    /** The cookie lives as long as the session may: its absolute lifetime (ADR-0034). */
    public KauthSessionCookies(ClientIpResolver clientIpResolver, KauthSessionProperties sessions) {
        this.clientIpResolver = clientIpResolver != null ? clientIpResolver : new ClientIpResolver(null);
        this.maxAgeSeconds = sessions.cookieMaxAgeSeconds();
    }

    /**
     * The value of the session cookie of the request; empty without it. A blank value (a cookie being removed)
     * counts as no cookie.
     */
    public static Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (KauthPref.SESSION_COOKIE_NAME.equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** Whether the request carries a session cookie (the CSRF check applies to it). */
    public static boolean present(HttpServletRequest request) {
        return read(request).isPresent();
    }

    /** Sets the session cookie. */
    public void issue(HttpServletRequest request, HttpServletResponse response, String rawToken) {
        add(response, cookie(request, KauthPref.SESSION_COOKIE_NAME, rawToken, maxAgeSeconds));
    }

    /** Removes the session cookie (sign-out). */
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        add(response, cookie(request, KauthPref.SESSION_COOKIE_NAME, "", 0));
    }

    private ResponseCookie cookie(HttpServletRequest request, String name, String value, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(clientIpResolver.isSecure(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private static void add(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
