package com.smartup24.cms.instance.kauth.security;

import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.repository.KauthApiTokenRepository;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.kauth.service.KauthApiTokenService;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KauthAuthenticationFilterTest {

    private KauthSessionService sessionService;
    private KauthApiTokenService apiTokenService;
    private MdUserService userService;
    private MdPermissionService permissionService;
    private KauthAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        sessionService = mock(KauthSessionService.class);
        apiTokenService = mock(KauthApiTokenService.class);
        userService = mock(MdUserService.class);
        permissionService = mock(MdPermissionService.class);
        filter = new KauthAuthenticationFilter(sessionService, apiTokenService, userService, permissionService);
    }

    @Test
    void doFilter_whenApiTokenUserLookupFailsWithDataAccessException_rethrowsException() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer dwh_test_token");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        var tokenRecord = new KauthApiTokenRepository.ApiTokenRecord(
                1L, 10L, "token", "dwh_", "hash", null, Instant.now(), null, null, 0L
        );
        when(apiTokenService.validateToken("dwh_test_token")).thenReturn(Optional.of(tokenRecord));
        when(userService.getUserById(10L)).thenThrow(new QueryTimeoutException("DB connection failure"));

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("DB connection failure");
    }

    @Test
    void doFilter_whenSessionUserLookupFailsWithDataAccessException_rethrowsException() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(KauthPref.SESSION_COOKIE_NAME, "test_session_token"));
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        var sessionRecord = new KauthSessionRepository.SessionRecord(
                1L, 10L, "hash", "127.0.0.1", "agent", "desktop", Instant.now(), Instant.now(), null, 0L
        );
        when(sessionService.getActiveSession("test_session_token")).thenReturn(Optional.of(sessionRecord));
        when(userService.getUserById(10L)).thenThrow(new QueryTimeoutException("DB connection timeout"));

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("DB connection timeout");
    }
}
