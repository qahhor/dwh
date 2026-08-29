package com.greenwhite.dwh.cp;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.repository.CpSessionRepository;
import com.greenwhite.dwh.cp.repository.CpUserRepository;
import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import com.greenwhite.dwh.cp.service.CpAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CpAuthServiceTest {

    private final CpUserRepository userRepository = Mockito.mock(CpUserRepository.class);
    private final CpSessionRepository sessionRepository = Mockito.mock(CpSessionRepository.class);
    private final CpPasswordHasher hasher = Mockito.mock(CpPasswordHasher.class);

    private final CpAuthService authService = new CpAuthService(userRepository, sessionRepository, hasher);

    @Test
    @DisplayName("Успешная аутентификация в Control Plane должна создавать сессию и возвращать токен и роли")
    void shouldLoginSuccessfully() {
        var user = new CpUserRepository.CpUser(1L, "Admin", "cpadmin", "admin@dev.local", "hash", CpPref.STATE_ACTIVE, Instant.now());
        when(userRepository.findByLogin("cpadmin")).thenReturn(Optional.of(user));
        when(hasher.verifyPassword("password123", "hash")).thenReturn(true);
        when(userRepository.getRoles(1L)).thenReturn(Set.of(CpPref.ROLE_ADMIN));

        var result = authService.login("cpadmin", "password123", "127.0.0.1", "Mozilla");

        assertThat(result).isNotNull();
        assertThat(result.login()).isEqualTo("cpadmin");
        assertThat(result.roles()).contains(CpPref.ROLE_ADMIN);
        assertThat(result.rawToken()).isNotNull().hasSizeGreaterThan(20);

        verify(sessionRepository, times(1)).create(eq(1L), anyString(), eq("127.0.0.1"), eq("Mozilla"));
    }

    @Test
    @DisplayName("Неверный пароль должен выбрасывать 401 Unauthorized")
    void shouldRejectInvalidPassword() {
        var user = new CpUserRepository.CpUser(1L, "Admin", "cpadmin", "admin@dev.local", "hash", CpPref.STATE_ACTIVE, Instant.now());
        when(userRepository.findByLogin("cpadmin")).thenReturn(Optional.of(user));
        when(hasher.verifyPassword("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("cpadmin", "wrong", "127.0.0.1", "Mozilla"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("Заблокированный пользователь должен выбрасывать 403 Forbidden")
    void shouldRejectBlockedUser() {
        var user = new CpUserRepository.CpUser(1L, "Blocked", "blocked", "b@dev.local", "hash", CpPref.STATE_PASSIVE, Instant.now());
        when(userRepository.findByLogin("blocked")).thenReturn(Optional.of(user));
        when(hasher.verifyPassword("pass", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login("blocked", "pass", "127.0.0.1", "Mozilla"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }
}
