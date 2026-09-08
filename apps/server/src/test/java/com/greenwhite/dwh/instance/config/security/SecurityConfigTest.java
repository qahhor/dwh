package com.greenwhite.dwh.instance.config.security;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.kauth.repository.KauthSessionRepository;
import com.greenwhite.dwh.instance.kauth.security.KauthAuthenticationFilter;
import com.greenwhite.dwh.instance.kauth.service.KauthApiTokenService;
import com.greenwhite.dwh.instance.kauth.service.KauthSessionService;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import com.greenwhite.dwh.instance.search.service.SearchPolicyProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
@WebMvcTest(controllers = {SecurityTestController.class,
        com.greenwhite.dwh.instance.kauth.controller.KauthPasswordController.class,
        com.greenwhite.dwh.instance.kauth.controller.OAuth2AuthController.class,
        com.greenwhite.dwh.instance.md.controller.MdI18nController.class,
        com.greenwhite.dwh.instance.md.controller.MdI18nAdminController.class})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(
        print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import({SecurityConfig.class, ProblemDetailAuthHandlers.class,
        KauthAuthenticationFilter.class, RateLimitFilter.class, RateLimitService.class, SearchPolicyProvider.class,
        com.greenwhite.dwh.instance.config.idempotency.IdempotencyFilter.class,
        SecurityTestController.class,
        com.greenwhite.dwh.instance.kauth.controller.KauthPasswordController.class})
class SecurityConfigTest {

    private static final String SESSION_COOKIE = "DWH_SESSION";

    @Autowired
    MockMvc mvc;

    @MockitoBean com.greenwhite.dwh.instance.search.repository.SearchSettingsRepository searchSettings;
    @Autowired SearchPolicyProvider searchPolicyProvider;
    @org.junit.jupiter.api.BeforeEach void initializeSearchPolicy() {
        searchPolicyProvider.publishCommitted(new com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.SettingsSnapshot(
                1, com.greenwhite.dwh.instance.search.service.SearchQueryPolicy.defaults()));
    }

    @MockitoBean
    KauthSessionService sessionService;
    @MockitoBean
    KauthApiTokenService apiTokenService;
    @MockitoBean
    MdUserService userService;
    @MockitoBean
    MdPermissionService permissionService;
    @MockitoBean
    AuditLogService auditLogService;
    @MockitoBean
    com.greenwhite.dwh.instance.config.idempotency.IdempotencyService idempotencyService;
    @MockitoBean
    com.greenwhite.dwh.instance.kauth.service.OAuth2AuthService oauth2AuthService;
    @MockitoBean
    com.greenwhite.dwh.instance.md.service.MdI18nService i18nService;
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
                        Instant.now(), Instant.now(), null, 0)));
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
    void authenticatedDictionaryReadDoesNotReplaceExistingCsrfCookie() throws Exception {
        stubAuthenticatedUser(Set.of());
        when(i18nService.effectiveDictionary("ru")).thenReturn(Map.of("auth.login", "Вход"));

        var result = mvc.perform(get("/api/v1/i18n/ru")
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session"),
                                new Cookie("XSRF-TOKEN", "existing-csrf")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['auth.login']").value("Вход"))
                .andReturn();

        assertNoCsrfReplacementOrHttpSession(result);
    }

    @Test
    void authenticatedPasswordMutationDoesNotReplaceExistingCsrfCookie() throws Exception {
        stubAuthenticatedUser(Set.of());

        var result = mvc.perform(passwordMutation()
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session"),
                                new Cookie("XSRF-TOKEN", "existing-csrf"))
                        .header("X-XSRF-TOKEN", "existing-csrf"))
                .andExpect(status().isNoContent())
                .andReturn();

        assertNoCsrfReplacementOrHttpSession(result);
    }

    @ParameterizedTest(name = "cookie fallback with {0} Bearer and {1} CSRF header is denied")
    @CsvSource({"invalid,missing", "invalid,empty", "invalid,mismatched",
            "empty,missing", "empty,empty", "empty,mismatched"})
    void bearerFallbackCannotBypassCookieCsrf(String bearerKind, String csrfKind) throws Exception {
        stubAuthenticatedUser(Set.of());
        when(apiTokenService.validateToken(anyString())).thenReturn(Optional.empty());
        var request = passwordMutation()
                .header("Authorization", bearerKind.equals("empty") ? "Bearer " : "Bearer invalid-token")
                .cookie(new Cookie(SESSION_COOKIE, "raw-session"), new Cookie("XSRF-TOKEN", "existing-csrf"));
        if (!csrfKind.equals("missing")) {
            request.header("X-XSRF-TOKEN", csrfKind.equals("empty") ? "" : "mismatched-csrf");
        }

        mvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf_token_invalid"));
        verify(userService, never()).changePassword(anyLong(), anyLong(), anyString(), anyString());
    }

    @ParameterizedTest(name = "cookie fallback with {0} Bearer accepts the matching CSRF pair")
    @ValueSource(strings = {"Bearer invalid-token", "Bearer "})
    void bearerFallbackWithMatchingCsrfRemainsUsable(String authorization) throws Exception {
        stubAuthenticatedUser(Set.of());
        when(apiTokenService.validateToken(anyString())).thenReturn(Optional.empty());

        mvc.perform(passwordMutation()
                        .header("Authorization", authorization)
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session"), new Cookie("XSRF-TOKEN", "existing-csrf"))
                        .header("X-XSRF-TOKEN", "existing-csrf"))
                .andExpect(status().isNoContent());
    }

    @Test
    void validatedBearerTakesPrecedenceOverCookieWithoutCsrf() throws Exception {
        // No usable cookie fixture: success must come from the accepted API token.
        when(apiTokenService.validateToken("valid-api-token")).thenReturn(Optional.of(
                new com.greenwhite.dwh.instance.kauth.repository.KauthApiTokenRepository.ApiTokenRecord(
                        19L, 7L, "test", "prefix", "hash", null, Instant.now(), null, null, 0)));
        when(userService.getUserById(7L)).thenReturn(activeUser());
        when(permissionService.getEffectivePermissions(7L)).thenReturn(Set.of());
        when(permissionService.getPermissionVersion(7L)).thenReturn(1L);

        mvc.perform(passwordMutation()
                        .header("Authorization", "Bearer valid-api-token")
                        .cookie(new Cookie(SESSION_COOKIE, "stale-session")))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @CsvSource({"absent,matching,204", "matching,mismatched,204", "empty,matching,403", "mismatched,matching,403"})
    void plainCsrfParameterRemainsSupportedButNeverOverridesHeader(String headerKind, String parameterKind,
                                                                int expectedStatus) throws Exception {
        stubAuthenticatedUser(Set.of());
        var request = passwordMutation()
                .cookie(new Cookie(SESSION_COOKIE, "raw-session"), new Cookie("XSRF-TOKEN", "existing-csrf"))
                .param("_csrf", parameterKind.equals("matching") ? "existing-csrf" : "mismatched-csrf");
        if (!headerKind.equals("absent")) {
            request.header("X-XSRF-TOKEN", switch (headerKind) {
                case "empty" -> "";
                case "matching" -> "existing-csrf";
                default -> "mismatched-csrf";
            });
        }
        mvc.perform(request).andExpect(status().is(expectedStatus));
        if (expectedStatus == 403) {
            verify(userService, never()).changePassword(anyLong(), anyLong(), anyString(), anyString());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "empty", "mismatched"})
    void cookieAuthenticationRejectsInvalidCsrfWithoutBearer(String headerKind) throws Exception {
        stubAuthenticatedUser(Set.of());
        var request = passwordMutation()
                .cookie(new Cookie(SESSION_COOKIE, "raw-session"), new Cookie("XSRF-TOKEN", "existing-csrf"));
        if (!headerKind.equals("missing")) request.header("X-XSRF-TOKEN", headerKind.equals("empty") ? "" : "wrong");
        mvc.perform(request).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf_token_invalid"));
        verify(userService, never()).changePassword(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void protectedMutationWithoutCredentialsRemainsUnauthorized() throws Exception {
        mvc.perform(passwordMutation()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
        verify(userService, never()).changePassword(anyLong(), anyLong(), anyString(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"inactive,false", "inactive,true", "stale-version,false", "stale-version,true"})
    void rejectedApiPrincipalFallsBackToCookieAndStillRequiresCsrf(String rejection, boolean matchingCsrf) throws Exception {
        stubAuthenticatedUser(Set.of());
        when(apiTokenService.validateToken("rejected-api-token")).thenReturn(Optional.of(
                new com.greenwhite.dwh.instance.kauth.repository.KauthApiTokenRepository.ApiTokenRecord(
                        19L, 8L, "test", "prefix", "hash", null, Instant.now(), null, null, 0)));
        when(userService.getUserById(8L)).thenReturn(new MdUserRepository.UserRecord(
                8L, "API User", "api-user", "api@example.test", null, "hash",
                rejection.equals("inactive") ? MdPref.STATE_PASSIVE : MdPref.STATE_ACTIVE,
                null, "ru", "UTC", null, Map.of(), false, false, null,
                Instant.now(), Instant.now(), null, null, rejection.equals("stale-version") ? 1 : 0));
        var request = passwordMutation().header("Authorization", "Bearer rejected-api-token")
                .cookie(new Cookie(SESSION_COOKIE, "raw-session"), new Cookie("XSRF-TOKEN", "existing-csrf"));
        if (matchingCsrf) request.header("X-XSRF-TOKEN", "existing-csrf");
        mvc.perform(request).andExpect(status().is(matchingCsrf ? 204 : 403));
        if (matchingCsrf) {
            verify(userService).changePassword(7L, 0, "OldPass-2026", "NewPass-2026!");
        } else {
            verify(userService, never()).changePassword(anyLong(), anyLong(), anyString(), anyString());
        }
    }

    private static MockHttpServletRequestBuilder passwordMutation() {
        return post("/api/v1/auth/password")
                .contentType("application/json")
                .content("{\"oldPassword\":\"OldPass-2026\",\"newPassword\":\"NewPass-2026!\"}");
    }

    private static void assertNoCsrfReplacementOrHttpSession(MvcResult result) {
        // Count only; assertion output must never contain a generated cookie/token value.
        long csrfWrites = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN=")).count();
        assertThat(csrfWrites).as("ordinary authenticated response CSRF cookie writes").isZero();
        assertThat(result.getRequest().getSession(false) == null).as("no servlet session created").isTrue();
        assertThat(result.getResponse().getCookie("JSESSIONID") == null).isTrue();
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
    @DisplayName("ASYNC dispatch после аутентифицированного запроса не проходит повторную авторизацию")
    void authenticatedAsyncDispatchIsNotRejectedAfterResponseStarts() throws Exception {
        when(sessionService.getActiveSession("raw-session")).thenReturn(Optional.of(
                new KauthSessionRepository.SessionRecord(
                        11L, 7L, "hash", "127.0.0.1", "ua", null,
                        Instant.now(), Instant.now(), null, 0)));
        when(userService.getUserById(7L)).thenReturn(activeUser());
        when(permissionService.getEffectivePermissions(7L)).thenReturn(Set.of("*.*"));
        when(permissionService.getPermissionVersion(7L)).thenReturn(1L);

        MvcResult initial = mvc.perform(get("/api/v1/security-test/async")
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    @DisplayName("Публичный путь без cookie: CSRF и авторизация не блокируют (404 — нет маппинга в срезе)")
    void publicPathWithoutAuth_passesSecurity() throws Exception {
        int status = mvc.perform(post("/api/v1/auth/login"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
    }

    @Test
    @DisplayName("Список языков и словарь доступны странице входа без сессии")
    void localizationReadsArePublic() throws Exception {
        when(i18nService.listLanguages(true)).thenReturn(java.util.List.of(
                new com.greenwhite.dwh.instance.md.i18n.I18nModels.LanguageSummary(
                        "ru", "Русский", true, true, 1, 70, 70, 100)));
        when(i18nService.effectiveDictionary("ru")).thenReturn(Map.of("auth.login", "Вход в систему"));

        mvc.perform(get("/api/v1/i18n/languages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ru"));
        mvc.perform(get("/api/v1/i18n/ru"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['auth.login']").value("Вход в систему"));
    }

    @Test
    @DisplayName("Административный API локализации не становится публичным")
    void localizationAdminApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/i18n/admin"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/i18n/admin/languages")
                        .contentType("application/json")
                        .content("{\"code\":\"fr\",\"name\":\"Français\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Нереализованный OAuth2 exchange не является публичным authentication boundary")
    void oauth2ExchangeWithoutSessionIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/oauth2/exchange")
                        .contentType("application/json")
                        .content("{\"provider\":\"google\",\"code\":\"forged\",\"email\":\"admin@example.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Callback модерации без аутентификации не может менять состояние модуля")
    void moderationCallbackWithoutSessionIsRejected() throws Exception {
        mvc.perform(post("/api/v1/modules/42/moderation-callback")
                        .contentType("application/json")
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Даже аутентифицированный пользователь не может вызвать незавершённый OAuth2 exchange")
    void oauth2ExchangeHasNoAuthenticatedRoute() throws Exception {
        stubAuthenticatedUser(Set.of("*.*"));

        mvc.perform(post("/api/v1/auth/oauth2/exchange")
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session"),
                                new Cookie("XSRF-TOKEN", "test-csrf-token"))
                        .header("X-XSRF-TOKEN", "test-csrf-token")
                        .contentType("application/json")
                        .content("{\"provider\":\"google\",\"code\":\"forged\",\"email\":\"admin@example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Callback модерации отсутствует и для аутентифицированного администратора")
    void moderationCallbackHasNoAuthenticatedRoute() throws Exception {
        stubAuthenticatedUser(Set.of("*.*"));

        mvc.perform(post("/api/v1/modules/42/moderation-callback")
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session"),
                                new Cookie("XSRF-TOKEN", "test-csrf-token"))
                        .header("X-XSRF-TOKEN", "test-csrf-token")
                        .contentType("application/json")
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Небезопасный runtime пользовательских модулей полностью удалён")
    void customModuleApiHasNoAuthenticatedRoute() throws Exception {
        stubAuthenticatedUser(Set.of("*.*"));

        mvc.perform(get("/api/v1/modules")
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session")))
                .andExpect(status().isNotFound());
    }

    private void stubAuthenticatedUser(Set<String> permissions) {
        when(sessionService.getActiveSession("raw-session")).thenReturn(Optional.of(
                new KauthSessionRepository.SessionRecord(
                        11L, 7L, "hash", "127.0.0.1", "ua", null,
                        Instant.now(), Instant.now(), null, 0)));
        when(userService.getUserById(7L)).thenReturn(activeUser());
        when(permissionService.getEffectivePermissions(7L)).thenReturn(permissions);
        when(permissionService.getPermissionVersion(7L)).thenReturn(1L);
    }

    private static MdUserRepository.UserRecord activeUser() {
        return new MdUserRepository.UserRecord(
                7L, "Test User", "test", "test@example.com", null, "hash",
                MdPref.STATE_ACTIVE, null, "ru", "UTC", null, Map.of(),
                false, false, null, Instant.now(), Instant.now(), null, null, 0);
    }

    // ------------------------------------------------------------------
    // Д-7: смена своего пароля живёт в контуре аутентификации
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Д-7: смена своего пароля требует аутентификации, но не права формы")
    void ownPasswordChangeNeedsAuthenticationNotPermission() throws Exception {
        when(sessionService.getActiveSession("raw-session")).thenReturn(Optional.of(
                new KauthSessionRepository.SessionRecord(
                        11L, 7L, "hash", "127.0.0.1", "ua", null,
                        Instant.now(), Instant.now(), null, 0)));
        when(userService.getUserById(7L)).thenReturn(activeUser());
        // Пусто — ни одного права: ровно положение роли auditor (ТЗ-01 разд. 4.4.1)
        when(permissionService.getEffectivePermissions(7L)).thenReturn(Set.of());
        when(permissionService.getPermissionVersion(7L)).thenReturn(1L);

        mvc.perform(post("/api/v1/auth/password")
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session"),
                                new Cookie("XSRF-TOKEN", "test-csrf-token"))
                        .header("X-XSRF-TOKEN", "test-csrf-token")
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"OldPass-2026\",\"newPassword\":\"NewPass-2026!\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Старый путь смены пароля продолжает работать: удаление эндпоинта — ломающее изменение")
    void legacyPasswordPathStillWorks() throws Exception {
        when(sessionService.getActiveSession("raw-session")).thenReturn(Optional.of(
                new KauthSessionRepository.SessionRecord(
                        11L, 7L, "hash", "127.0.0.1", "ua", null,
                        Instant.now(), Instant.now(), null, 0)));
        when(userService.getUserById(7L)).thenReturn(activeUser());
        when(permissionService.getEffectivePermissions(7L)).thenReturn(Set.of());
        when(permissionService.getPermissionVersion(7L)).thenReturn(1L);

        mvc.perform(post("/api/v1/iam/users/me/password")
                        .cookie(new Cookie(SESSION_COOKIE, "raw-session"),
                                new Cookie("XSRF-TOKEN", "test-csrf-token"))
                        .header("X-XSRF-TOKEN", "test-csrf-token")
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"OldPass-2026\",\"newPassword\":\"NewPass-2026!\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Без аутентификации смена пароля отклоняется: путь не публичный")
    void ownPasswordChangeRejectedWithoutSession() throws Exception {
        mvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"a\",\"newPassword\":\"b\"}"))
                .andExpect(status().isUnauthorized());
    }
}
