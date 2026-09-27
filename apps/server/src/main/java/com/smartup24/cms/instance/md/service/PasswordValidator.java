package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Валидатор паролей пользователей (FR-USR-2):
 * - Длина от {@value #MIN_PASSWORD_LENGTH} до {@value #MAX_PASSWORD_LENGTH} символов (решение 27.09.2026). Проверяется
 *   только новый пароль: вход с прежним, более длинным паролем продолжает работать.
 * - Запрет распространённых слабых паролей (blacklist).
 * - Запрет использования логина в качестве пароля.
 */
@Component
public class PasswordValidator {

    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 20;

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password123",
            "password1234",
            "1234567890",
            "12345678901",
            "qwerty12345",
            "admin12345",
            "administrator",
            "welcome1234",
            "letmein1234",
            "changeme123",
            "iloveyou123",
            "sunshine123",
            "trustno1123",
            "master12345",
            "football123",
            "monkey12345",
            "shadow12345",
            "princess123",
            "dragon12345",
            "superman123");

    public void validate(String password, String login) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw ApiException.badRequest(
                    ErrorCode.PASSWORD_POLICY,
                    "Пароль должен содержать от " + MIN_PASSWORD_LENGTH + " до " + MAX_PASSWORD_LENGTH + " символов");
        }

        String lower = password.toLowerCase().trim();

        if (COMMON_PASSWORDS.contains(lower)) {
            throw ApiException.badRequest(
                    ErrorCode.PASSWORD_POLICY,
                    "Выбранный пароль слишком прост и входит в список скомпрометированных. Придумайте более надёжный пароль.");
        }

        if (login != null
                && !login.isBlank()
                && lower.contains(login.toLowerCase().trim())) {
            throw ApiException.badRequest(ErrorCode.PASSWORD_POLICY, "Пароль не должен содержать логин пользователя");
        }
    }
}
