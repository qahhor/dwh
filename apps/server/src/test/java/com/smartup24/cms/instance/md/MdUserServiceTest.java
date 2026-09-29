package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserSecurityService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.PasswordHasher;
import com.smartup24.cms.instance.md.service.PasswordValidator;
import com.smartup24.cms.instance.md.service.UserSessionInvalidator;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MdUserServiceTest {

    private final MdUserRepository userRepository = Mockito.mock(MdUserRepository.class);
    private final MdRoleRepository roleRepository = Mockito.mock(MdRoleRepository.class);
    private final MdCustomFieldService customFieldService = Mockito.mock(MdCustomFieldService.class);
    private final PasswordHasher passwordHasher = Mockito.mock(PasswordHasher.class);
    private final UserSessionInvalidator sessionInvalidator = Mockito.mock(UserSessionInvalidator.class);
    private final SearchChangePublisher searchChangePublisher = Mockito.mock(SearchChangePublisher.class);
    private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

    private final PasswordValidator passwordValidator = new PasswordValidator();

    private final MdScopeService scopeService = Mockito.mock(MdScopeService.class);

    private final MdUserService userService = new MdUserService(
            userRepository,
            roleRepository,
            customFieldService,
            passwordHasher,
            passwordValidator,
            searchChangePublisher,
            auditLogService,
            scopeService);

    private final MdUserSecurityService userSecurityService = new MdUserSecurityService(
            userRepository,
            passwordHasher,
            passwordValidator,
            sessionInvalidator,
            searchChangePublisher,
            auditLogService);

    @Test
    @DisplayName("Блокировка суперпользователя admin должна отклоняться инвариантом I-IAM-1")
    void shouldPreventAdminUserFromBeingBlocked() {
        var adminUser = new MdUserRepository.UserRecord(
                1L,
                "System Admin",
                "admin",
                "admin@company.com",
                "+998901234567",
                "$argon2id$...",
                "A",
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                1L,
                1L,
                0,
                1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));

        assertThatThrownBy(() -> userSecurityService.setUserState(1L, MdPref.STATE_PASSIVE, 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.admin_block_forbidden");
    }

    @Test
    @DisplayName("Удаление (анонимизация) суперпользователя admin должна отклоняться (I-IAM-1)")
    void shouldPreventAdminUserFromBeingAnonymized() {
        var adminUser = new MdUserRepository.UserRecord(
                1L,
                "System Admin",
                "admin",
                "admin@company.com",
                "+998901234567",
                "$argon2id$...",
                "A",
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                1L,
                1L,
                0,
                1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));

        assertThatThrownBy(() -> userSecurityService.anonymizeUser(1L, 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.admin_delete_forbidden");
    }

    @Test
    @DisplayName("Создание пользователя с паролем короче 8 символов должно отклоняться (FR-USR-2)")
    void shouldRejectPasswordShorterThanEight() {
        assertThatThrownBy(() -> userService.createUser(
                        "Test User",
                        "testuser",
                        "test@company.com",
                        null,
                        "Short1!",
                        null,
                        "ru",
                        "UTC",
                        null,
                        Map.of(),
                        false,
                        null,
                        1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.password_length")
                .hasFieldOrPropertyWithValue("params", Map.of("min", 8, "max", 20));
    }

    @Test
    @DisplayName("Создание пользователя с паролем из словаря скомпрометированных должно отклоняться (FR-USR-2)")
    void shouldRejectCommonWeakPasswordFromDictionary() {
        assertThatThrownBy(() -> userService.createUser(
                        "Test User",
                        "testuser",
                        "test@company.com",
                        null,
                        "password1234",
                        null,
                        "ru",
                        "UTC",
                        null,
                        Map.of(),
                        false,
                        null,
                        1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.password_common");
    }

    @Test
    @DisplayName("Создание пользователя с паролем, содержащим логин, должно отклоняться (FR-USR-2)")
    void shouldRejectPasswordContainingLogin() {
        assertThatThrownBy(() -> userService.createUser(
                        "Test User",
                        "testuser",
                        "test@company.com",
                        null,
                        "My_testuser_26",
                        null,
                        "ru",
                        "UTC",
                        null,
                        Map.of(),
                        false,
                        null,
                        1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.password_contains_login");
    }

    @Test
    @DisplayName("Создание пользователя с дублирующимся телефоном должно отклоняться (FR-USR-1)")
    void shouldRejectDuplicatePhoneForActiveUser() {
        when(userRepository.existsByPhone("+998901234567")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(
                        "Test User",
                        "testuser",
                        "test@company.com",
                        "+998901234567",
                        "StrongPassword2026!",
                        null,
                        "ru",
                        "UTC",
                        null,
                        Map.of(),
                        false,
                        null,
                        1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.user_phone_exists");
    }

    @Test
    @DisplayName("Смена пароля с неверным старым паролем должна отклоняться (FR-USR-7)")
    void shouldRejectPasswordChangeWhenOldPasswordInvalid() {
        var user = new MdUserRepository.UserRecord(
                2L,
                "Normal User",
                "user2",
                "user2@company.com",
                null,
                "$argon2id$hashed",
                "A",
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                1L,
                1L,
                0,
                1L);

        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(passwordHasher.verifyPassword("WrongOldPassword!", "$argon2id$hashed"))
                .thenReturn(false);

        assertThatThrownBy(() -> userSecurityService.changePassword(2L, 0, "WrongOldPassword!", "NewValidPass2026!"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.md.current_password_invalid");
    }

    @Test
    @DisplayName("Анонимизация пользователя должна вызывать репозиторий и отзывать сессии (FR-USR-8)")
    void shouldAnonymizeUserAndInvalidateSessions() {
        var user = new MdUserRepository.UserRecord(
                2L,
                "Normal User",
                "user2",
                "user2@company.com",
                null,
                "$argon2id$hashed",
                "A",
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                1L,
                1L,
                0,
                1L);

        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        userSecurityService.anonymizeUser(2L, 1L);

        Mockito.verify(userRepository).anonymizeUser(2L, 1L);
        Mockito.verify(sessionInvalidator).invalidateAllAccess(2L);
    }
}
