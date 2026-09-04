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
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R3 (ремедиация, ADR-0008 разд. 2.2): DoD — превышение лимита -> 429 + Retry-After
 * + событие rate_limit_exceeded в security-журнале (ровно одно на окно, анти-флуд).
 */
@WebMvcTest(controllers = SecurityTestController.class)
@Import({SecurityConfig.class, ProblemDetailAuthHandlers.class,
        KauthAuthenticationFilter.class, RateLimitFilter.class, RateLimitService.class,
        com.greenwhite.dwh.instance.config.idempotency.IdempotencyFilter.class,
        SecurityTestController.class})
@TestPropertySource(properties = {
        "dwh.rate-limit.ip-per-minute=2",
        "dwh.rate-limit.user-per-minute=3",
        "dwh.rate-limit.expensive-per-minute=1"
})
class RateLimitFilterTest {

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
    @MockitoBean
    AuditLogService auditLogService;
    @MockitoBean
    com.greenwhite.dwh.instance.config.idempotency.IdempotencyService idempotencyService;


    @Test
    @DisplayName("IP-лимит: 3-й неаутентифицированный запрос -> 429 + Retry-After + событие в журнале")
    void ipLimitExceeded_returns429AndLogsSecurityEvent() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.9.9.1"); return r; }));
        }
        mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("10.9.9.1"); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));

        verify(auditLogService, times(1)).logSecurityEvent(
                eq(RateLimitFilter.EVENT_RATE_LIMIT_EXCEEDED), isNull(), eq("10.9.9.1"), any(), any());
    }

    @Test
    @DisplayName("Лимит пользователя считается отдельно от IP и привязан к user_id")
    void userLimitTracksAuthenticatedUser() throws Exception {
        mockAuthenticatedUser(7L, "session-7");

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/security-test")
                            .with(r -> { r.setRemoteAddr("10.9.9.2"); return r; })
                            .cookie(new Cookie("DWH_SESSION", "session-7")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/security-test")
                        .with(r -> { r.setRemoteAddr("10.9.9.2"); return r; })
                        .cookie(new Cookie("DWH_SESSION", "session-7")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"));

        verify(auditLogService).logSecurityEvent(
                eq(RateLimitFilter.EVENT_RATE_LIMIT_EXCEEDED), eq(7L), anyString(), any(), any());
    }

    @Test
    @DisplayName("Дорогой путь (/api/v1/search/**) ограничен строже обычного")
    void expensivePathUsesStricterLimit() throws Exception {
        mockAuthenticatedUser(8L, "session-8");

        mvc.perform(get("/api/v1/search").param("q", "x")
                        .with(r -> { r.setRemoteAddr("10.9.9.3"); return r; })
                        .cookie(new Cookie("DWH_SESSION", "session-8")));
        mvc.perform(get("/api/v1/search").param("q", "x")
                        .with(r -> { r.setRemoteAddr("10.9.9.3"); return r; })
                        .cookie(new Cookie("DWH_SESSION", "session-8")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Дорогие search и audit пути не должны исчерпывать общий лимит друг друга")
    void expensivePathFamiliesUseIndependentBuckets() throws Exception {
        mockAuthenticatedUser(9L, "session-9");

        mvc.perform(get("/api/v1/search").param("q", "admin")
                        .cookie(new Cookie("DWH_SESSION", "session-9")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/audit/logs")
                        .cookie(new Cookie("DWH_SESSION", "session-9")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/search").param("q", "admin")
                        .cookie(new Cookie("DWH_SESSION", "session-9")))
                .andExpect(status().isTooManyRequests());
    }

    private void mockAuthenticatedUser(long userId, String rawSession) {
        when(sessionService.getActiveSession(rawSession)).thenReturn(Optional.of(
                new KauthSessionRepository.SessionRecord(
                        userId, userId, "hash", "127.0.0.1", "ua", null,
                        Instant.now(), Instant.now(), null)));
        when(userService.getUserById(userId)).thenReturn(new MdUserRepository.UserRecord(
                userId, "U" + userId, "u" + userId, "u" + userId + "@x", null, "hash",
                MdPref.STATE_ACTIVE, null, "ru", "UTC", null, Map.of(),
                false, false, null, Instant.now(), Instant.now(), null, null));
        when(permissionService.getEffectivePermissions(userId)).thenReturn(Set.of("*.*"));
        when(permissionService.getPermissionVersion(userId)).thenReturn(1L);
    }
}
