package com.smartup24.cms.instance.kauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.pref.KauthSessionProperties;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Plan 10/10, item 4.7: the session cookie SMC_SESSION; the name before the rename is not read. */
class KauthSessionCookiesTest {

    /** The session cookie name before plan 10/10, item 4.7. */
    private static final String OLD_NAME = "DWH_SESSION";

    private final KauthSessionCookies cookies = new KauthSessionCookies(null);

    @Test
    @DisplayName("SMC_SESSION is read; the old name and a blank value are no cookie")
    void readsOnlyTheCurrentName() {
        var both = new MockHttpServletRequest();
        both.setCookies(new Cookie(OLD_NAME, "old"), new Cookie(KauthPref.SESSION_COOKIE_NAME, "new"));
        assertThat(KauthSessionCookies.read(both)).contains("new");

        var old = new MockHttpServletRequest();
        old.setCookies(new Cookie(OLD_NAME, "old"));
        assertThat(KauthSessionCookies.read(old)).isEmpty();
        assertThat(KauthSessionCookies.present(old)).isFalse();

        var blank = new MockHttpServletRequest();
        blank.setCookies(new Cookie(KauthPref.SESSION_COOKIE_NAME, " "));
        assertThat(KauthSessionCookies.read(blank)).isEmpty();
        assertThat(KauthSessionCookies.present(new MockHttpServletRequest())).isFalse();
    }

    @Test
    @DisplayName("issuing sets an HTTP-only SMC_SESSION and leaves an old cookie alone")
    void issueSetsTheCookie() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(OLD_NAME, "raw"));
        var response = new MockHttpServletResponse();

        cookies.issue(request, response, "raw");

        assertThat(response.getCookie(KauthPref.SESSION_COOKIE_NAME).getValue()).isEqualTo("raw");
        assertThat(response.getCookie(KauthPref.SESSION_COOKIE_NAME).isHttpOnly())
                .isTrue();
        assertThat(response.getCookie(KauthPref.SESSION_COOKIE_NAME).getMaxAge())
                .isEqualTo((int) Duration.ofDays(7).toSeconds());
        assertThat(response.getCookie(OLD_NAME)).isNull();
    }

    @Test
    @DisplayName("7.5: the cookie Max-Age is the absolute session lifetime")
    void cookieLivesAsLongAsTheSession() {
        var shortLived = new KauthSessionCookies(
                null, new KauthSessionProperties(Duration.ofHours(8), Duration.ofHours(2), null, null));
        var response = new MockHttpServletResponse();

        shortLived.issue(new MockHttpServletRequest(), response, "raw");

        assertThat(response.getCookie(KauthPref.SESSION_COOKIE_NAME).getMaxAge())
                .isEqualTo((int) Duration.ofHours(8).toSeconds());
    }

    @Test
    @DisplayName("sign-out removes SMC_SESSION")
    void clearRemovesTheCookie() {
        var response = new MockHttpServletResponse();

        cookies.clear(new MockHttpServletRequest(), response);

        assertThat(response.getCookie(KauthPref.SESSION_COOKIE_NAME).getMaxAge())
                .isZero();
        assertThat(response.getCookie(OLD_NAME)).isNull();
    }
}
