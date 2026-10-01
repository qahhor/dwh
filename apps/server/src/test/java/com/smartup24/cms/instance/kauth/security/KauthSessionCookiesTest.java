package com.smartup24.cms.instance.kauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.kauth.pref.KauthPref;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Plan 10/10, item 4.7: the session cookie SMC_SESSION, with DWH_SESSION read until the sunset. */
class KauthSessionCookiesTest {

    private final KauthSessionCookies cookies = new KauthSessionCookies(null);

    @Test
    @DisplayName("the new name wins; the old one is read and marked as legacy; a blank value is no cookie")
    void readsTheNewNameThenTheOldOne() {
        var both = new MockHttpServletRequest();
        both.setCookies(
                new Cookie(KauthPref.LEGACY_SESSION_COOKIE_NAME, "old"),
                new Cookie(KauthPref.SESSION_COOKIE_NAME, "new"));
        assertThat(KauthSessionCookies.read(both)).contains(new KauthSessionCookies.SessionCookie("new", false));

        var legacy = new MockHttpServletRequest();
        legacy.setCookies(new Cookie(KauthPref.LEGACY_SESSION_COOKIE_NAME, "old"));
        assertThat(KauthSessionCookies.read(legacy)).contains(new KauthSessionCookies.SessionCookie("old", true));

        var blank = new MockHttpServletRequest();
        blank.setCookies(
                new Cookie(KauthPref.LEGACY_SESSION_COOKIE_NAME, ""), new Cookie(KauthPref.SESSION_COOKIE_NAME, " "));
        assertThat(KauthSessionCookies.read(blank)).isEmpty();
        assertThat(KauthSessionCookies.present(new MockHttpServletRequest())).isFalse();
    }

    @Test
    @DisplayName("issuing sets SMC_SESSION and removes DWH_SESSION only when the browser still sends it")
    void issueReplacesTheOldCookie() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(KauthPref.LEGACY_SESSION_COOKIE_NAME, "raw"));
        var response = new MockHttpServletResponse();

        cookies.issue(request, response, "raw");

        assertThat(response.getCookie(KauthPref.SESSION_COOKIE_NAME).getValue()).isEqualTo("raw");
        assertThat(response.getCookie(KauthPref.SESSION_COOKIE_NAME).isHttpOnly())
                .isTrue();
        assertThat(response.getCookie(KauthPref.LEGACY_SESSION_COOKIE_NAME).getMaxAge())
                .isZero();

        var fresh = new MockHttpServletResponse();
        cookies.issue(new MockHttpServletRequest(), fresh, "raw");
        assertThat(fresh.getCookie(KauthPref.LEGACY_SESSION_COOKIE_NAME)).isNull();
    }

    @Test
    @DisplayName("sign-out removes the cookie under both names")
    void clearRemovesBothNames() {
        var response = new MockHttpServletResponse();

        cookies.clear(new MockHttpServletRequest(), response);

        assertThat(response.getCookie(KauthPref.SESSION_COOKIE_NAME).getMaxAge())
                .isZero();
        assertThat(response.getCookie(KauthPref.LEGACY_SESSION_COOKIE_NAME).getMaxAge())
                .isZero();
    }
}
