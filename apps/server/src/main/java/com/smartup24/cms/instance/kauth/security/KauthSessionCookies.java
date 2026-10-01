package com.smartup24.cms.instance.kauth.security;

import com.smartup24.cms.instance.common.security.ClientIpResolver;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * The session cookie in one place (plan 10/10, item 4.7): the server sets {@link KauthPref#SESSION_COOKIE_NAME};
 * until {@code ApiDeprecations.SUNSET} it also reads {@link KauthPref#LEGACY_SESSION_COOKIE_NAME} and replaces it with
 * the new name on the same response, so a browser signed in before the rename keeps its session.
 */
public class KauthSessionCookies {

    /** The value of the session cookie a request carries, and whether it came under the old name. */
    public record SessionCookie(String value, boolean legacy) {}

    private final ClientIpResolver clientIpResolver;

    public KauthSessionCookies(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver != null ? clientIpResolver : new ClientIpResolver(null);
    }

    /**
     * The session cookie of the request: the new name first, then the old one; empty without either. A blank value
     * (a cookie being removed) counts as no cookie.
     */
    public static Optional<SessionCookie> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String legacy = null;
        for (Cookie cookie : cookies) {
            if (cookie.getValue() == null || cookie.getValue().isBlank()) {
                continue;
            }
            if (KauthPref.SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return Optional.of(new SessionCookie(cookie.getValue(), false));
            }
            if (KauthPref.LEGACY_SESSION_COOKIE_NAME.equals(cookie.getName())) {
                legacy = cookie.getValue();
            }
        }
        return legacy == null ? Optional.empty() : Optional.of(new SessionCookie(legacy, true));
    }

    /** Whether the request carries a session cookie under either name (the CSRF check applies to it). */
    public static boolean present(HttpServletRequest request) {
        return read(request).isPresent();
    }

    /** Sets the session cookie under the new name and removes the old one if the browser still sends it. */
    public void issue(HttpServletRequest request, HttpServletResponse response, String rawToken) {
        add(
                response,
                cookie(request, KauthPref.SESSION_COOKIE_NAME, rawToken, KauthPref.SESSION_COOKIE_MAX_AGE_SECONDS));
        if (carries(request, KauthPref.LEGACY_SESSION_COOKIE_NAME)) {
            add(response, cookie(request, KauthPref.LEGACY_SESSION_COOKIE_NAME, "", 0));
        }
    }

    /** Removes the session cookie under both names (sign-out). */
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        add(response, cookie(request, KauthPref.SESSION_COOKIE_NAME, "", 0));
        add(response, cookie(request, KauthPref.LEGACY_SESSION_COOKIE_NAME, "", 0));
    }

    private ResponseCookie cookie(HttpServletRequest request, String name, String value, int maxAgeSeconds) {
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

    private static boolean carries(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }
}
