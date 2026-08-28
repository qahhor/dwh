package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import com.greenwhite.dwh.instance.md.service.PasswordHasher;
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

    private final MdUserService userService = new MdUserService(
            userRepository, roleRepository, permissionService, customFieldService, passwordHasher,
            sessionInvalidator
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
}
