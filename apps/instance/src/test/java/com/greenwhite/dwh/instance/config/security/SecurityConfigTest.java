package com.greenwhite.dwh.instance.config.security;

import com.greenwhite.dwh.instance.kauth.repository.KauthSessionRepository;
import com.greenwhite.dwh.instance.kauth.security.KauthAuthenticationFilter;
import com.greenwhite.dwh.instance.kauth.service.KauthApiTokenService;
import com.greenwhite.dwh.instance.kauth.service.KauthSessionService;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2 (ремедиация, ADR-0008): CSRF double-submit, интеграция аутентификации
 * в Spring Security, заголовки безопасности, RFC 9457 на 401/403.
 * Матрица результатов ТЗ-01 разд. 8.2, блок SEC:
 * «мутирующий запрос без CSRF-токена -> 403».
 */
@WebMvcTest(controllers = SecurityTestController.class)
@Import({SecurityConfig.class, ProblemDetailAuthHandlers.class,
        KauthAuthenticationFilter.class, SecurityTestController.class})
class SecurityConfigTest {

    private static final String SESSION_COOKIE = "DWH_SESSION";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    KauthSessionService sessionService;
    @MockitoBean
    KauthApiTokenService apiTokenService;
    @MockitoBean
    MdUserService userService;
    @MockitoBean
    MdPermissionService permissionService;

    @Test
    @DisplayName("FR-SEC-1: мутирующий запрос с cookie-сессией без CSRF-токена -> 403 csrf_token_invalid")
    void mutatingWithSessionCookieWithoutCsrf_returns403() throws Exception {
        mvc.perform(post("/api/v1/security-test")
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("csrf_token_invalid"));
    }

    @Test
    @DisplayName("FR-AUTH-6: Bearer-запрос освобождён от CSRF; невалидный токен -> 401 unauthorized (RFC 9457)")
    void mutatingWithBearer_skipsCsrf_unauthenticated401() throws Exception {
        when(apiTokenService.validateToken(anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/security-test")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    @DisplayName("Валидная сессия + CSRF-токен -> 200, аутентификация доходит до контроллера")
    void mutatingWithSessionAndCsrf_returns200() throws Exception {
        when(sessionService.getActiveSession("raw-session")).thenReturn(Optional.of(
                new KauthSessionRepository.SessionRecord(
                        11L, 7L, "hash", "127.0.0.1", "ua", null,
                        Instant.now(), Instant.now(), null)));
        when(userService.getUserById(7L)).thenReturn(activeUser());
        when(permissionService.getEffectivePermissions(7L)).thenReturn(Set.of("*.*"));
        when(permissionService.getPermissionVersion(7L)).thenReturn(1L);

        // Double-submit как делает Angular: cookie XSRF-TOKEN + тот же токен в X-XSRF-TOKEN
        mvc.perform(post("/api/v1/security-test")
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session"),
                                new Cookie("XSRF-TOKEN", "test-csrf-token"))
                        .header("X-XSRF-TOKEN", "test-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    @DisplayName("FR-SEC-5: заголовки безопасности присутствуют в каждом ответе")
    void responseCarriesSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/security-test").secure(true))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("Permissions-Policy", containsString("geolocation=()")))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    @Test
    @DisplayName("Публичный путь без cookie: CSRF и авторизация не блокируют (404 — нет маппинга в срезе)")
    void publicPathWithoutAuth_passesSecurity() throws Exception {
        int status = mvc.perform(post("/api/v1/auth/login"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
    }

    private static MdUserRepository.UserRecord activeUser() {
        return new MdUserRepository.UserRecord(
                7L, "Test User", "test", "test@example.com", null, "hash",
                MdPref.STATE_ACTIVE, null, "ru", "UTC", null, Map.of(),
                false, false, null, Instant.now(), Instant.now(), null, null);
    }
}
