package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import com.greenwhite.dwh.instance.md.service.PasswordHasher;
import com.greenwhite.dwh.instance.md.service.PasswordValidator;
import com.greenwhite.dwh.instance.md.service.UserSessionInvalidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class MdUserServiceTest {

    private final MdUserRepository userRepository = Mockito.mock(MdUserRepository.class);
    private final MdRoleRepository roleRepository = Mockito.mock(MdRoleRepository.class);
    private final MdPermissionService permissionService = Mockito.mock(MdPermissionService.class);
    private final MdCustomFieldService customFieldService = Mockito.mock(MdCustomFieldService.class);
    private final PasswordHasher passwordHasher = Mockito.mock(PasswordHasher.class);
    private final UserSessionInvalidator sessionInvalidator = Mockito.mock(UserSessionInvalidator.class);
    private final com.greenwhite.dwh.instance.search.typesense.TypesenseIndexer typesenseIndexer =
            Mockito.mock(com.greenwhite.dwh.instance.search.typesense.TypesenseIndexer.class);
    private final com.greenwhite.dwh.instance.audit.service.AuditLogService auditLogService =
            Mockito.mock(com.greenwhite.dwh.instance.audit.service.AuditLogService.class);


    private final PasswordValidator passwordValidator = new PasswordValidator();

    private final com.greenwhite.dwh.instance.md.service.MdScopeService scopeService =
            Mockito.mock(com.greenwhite.dwh.instance.md.service.MdScopeService.class);

    private final MdUserService userService = new MdUserService(
            userRepository, roleRepository, permissionService, customFieldService, passwordHasher,
            passwordValidator, sessionInvalidator, typesenseIndexer, auditLogService, scopeService
    );



    @Test
    @DisplayName("Блокировка суперпользователя admin должна отклоняться инвариантом I-IAM-1")
    void shouldPreventAdminUserFromBeingBlocked() {
        var adminUser = new MdUserRepository.UserRecord(
                1L, "System Admin", "admin", "admin@company.com", "+998901234567",
                "$argon2id$...", "A", null, "ru", "UTC", null, Map.of(), false, false,
                Instant.now(), Instant.now(), Instant.now(), 1L, 1L
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));

        assertThatThrownBy(() -> userService.setUserState(1L, MdPref.STATE_PASSIVE, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Системный администратор не может быть заблокирован");
    }

    @Test
    @DisplayName("Удаление (анонимизация) суперпользователя admin должна отклоняться (I-IAM-1)")
    void shouldPreventAdminUserFromBeingAnonymized() {
        var adminUser = new MdUserRepository.UserRecord(
                1L, "System Admin", "admin", "admin@company.com", "+998901234567",
                "$argon2id$...", "A", null, "ru", "UTC", null, Map.of(), false, false,
                Instant.now(), Instant.now(), Instant.now(), 1L, 1L
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));

        assertThatThrownBy(() -> userService.anonymizeUser(1L, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Системный администратор не может быть удалён");
    }

    @Test
    @DisplayName("Создание пользователя с паролем короче 10 символов должно отклоняться (FR-USR-2)")
    void shouldRejectWeakPasswordLengthLessThan10() {
        assertThatThrownBy(() -> userService.createUser(
                "Test User", "testuser", "test@company.com", null, "Short1!",
                null, "ru", "UTC", null, Map.of(), false, null, 1L
        )).isInstanceOf(ApiException.class)
          .hasMessageContaining("Пароль должен содержать минимум 10 символов");
    }

    @Test
    @DisplayName("Создание пользователя с паролем из словаря скомпрометированных должно отклоняться (FR-USR-2)")
    void shouldRejectCommonWeakPasswordFromDictionary() {
        assertThatThrownBy(() -> userService.createUser(
                "Test User", "testuser", "test@company.com", null, "password1234",
                null, "ru", "UTC", null, Map.of(), false, null, 1L
        )).isInstanceOf(ApiException.class)
          .hasMessageContaining("слишком прост и входит в список скомпрометированных");
    }

    @Test
    @DisplayName("Создание пользователя с паролем, содержащим логин, должно отклоняться (FR-USR-2)")
    void shouldRejectPasswordContainingLogin() {
        assertThatThrownBy(() -> userService.createUser(
                "Test User", "testuser", "test@company.com", null, "MySecret_testuser_2026",
                null, "ru", "UTC", null, Map.of(), false, null, 1L
        )).isInstanceOf(ApiException.class)
          .hasMessageContaining("не должен содержать логин");
    }

    @Test
    @DisplayName("Создание пользователя с дублирующимся телефоном должно отклоняться (FR-USR-1)")
    void shouldRejectDuplicatePhoneForActiveUser() {
        when(userRepository.existsByPhone("+998901234567")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(
                "Test User", "testuser", "test@company.com", "+998901234567", "StrongPassword2026!",
                null, "ru", "UTC", null, Map.of(), false, null, 1L
        )).isInstanceOf(ApiException.class)
          .hasMessageContaining("с таким номером телефона уже существует");
    }

    @Test
    @DisplayName("Смена пароля с неверным старым паролем должна отклоняться (FR-USR-7)")
    void shouldRejectPasswordChangeWhenOldPasswordInvalid() {
        var user = new MdUserRepository.UserRecord(
                2L, "Normal User", "user2", "user2@company.com", null,
                "$argon2id$hashed", "A", null, "ru", "UTC", null, Map.of(), false, false,
                Instant.now(), Instant.now(), Instant.now(), 1L, 1L
        );

        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(passwordHasher.verifyPassword("WrongOldPassword!", "$argon2id$hashed")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(2L, "WrongOldPassword!", "NewValidPassword2026!"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Неверный текущий пароль");

    }

    @Test
    @DisplayName("Анонимизация пользователя должна вызывать репозиторий и отзывать сессии (FR-USR-8)")
    void shouldAnonymizeUserAndInvalidateSessions() {
        var user = new MdUserRepository.UserRecord(
                2L, "Normal User", "user2", "user2@company.com", null,
                "$argon2id$hashed", "A", null, "ru", "UTC", null, Map.of(), false, false,
                Instant.now(), Instant.now(), Instant.now(), 1L, 1L
        );

        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        userService.anonymizeUser(2L, 1L);

        Mockito.verify(userRepository).anonymizeUser(2L, 1L);
        Mockito.verify(sessionInvalidator).invalidateAllAccess(2L);
    }
}

