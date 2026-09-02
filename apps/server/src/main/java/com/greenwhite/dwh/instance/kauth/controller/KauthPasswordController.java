package com.greenwhite.dwh.instance.kauth.controller;

import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.md.service.MdUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Смена собственного пароля — контур аутентификации, а не бизнес-форма (Д-7).
 *
 * Раньше эндпоинт жил в {@code MdUserController} и требовал права
 * {@code iam.profile:update}. Из-за этого роль {@code auditor}, которой ТЗ-01
 * разд. 4.4.1 не даёт ни одного мутирующего действия, не могла сменить себе
 * пароль. Учётная запись аудитора с {@code force_password_change = true}
 * блокировалась навсегда: система требовала сменить пароль и сама же запрещала.
 *
 * Решение CEO (30.08, вариант «б» из AUDIT-05): смена своего пароля выводится
 * из матрицы форм туда же, где вход и выход. Это снимает противоречие в корне —
 * собственные учётные данные не являются данными экземпляра, и право на форму
 * к ним отношения не имеет. Определение роли аудитора при этом не размывается.
 *
 * Аутентификация обязательна: путь не входит в {@code PUBLIC_PATHS}, поэтому
 * его закрывает общее правило {@code anyRequest().authenticated()}, а старый
 * пароль проверяется отдельно в {@link MdUserService#changePassword}.
 *
 * Старый путь сохранён как псевдоним: удаление эндпоинта — ломающее изменение
 * и требует {@code /api/v2} (ТЗ-04 разд. 9).
 */
@RestController
@RequestMapping({"/api/v1/auth", "/api/v1/iam/users/me"})
public class KauthPasswordController {

    private final MdUserService userService;

    public KauthPasswordController(MdUserService userService) {
        this.userService = userService;
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changeMyPassword(@Valid @RequestBody ChangePasswordDto body) {
        userService.changePassword(SecurityContext.getCurrentUserId(), body.oldPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record ChangePasswordDto(
            @NotBlank String oldPassword,
            @NotBlank @Size(min = 10, max = 100) String newPassword
    ) {}
}
