package com.smartup24.cms.instance.kauth.controller;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdUserSecurityService;
import com.smartup24.cms.instance.md.service.PasswordValidator;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Changing one's own password belongs to authentication, not to a business form.
 *
 * The endpoint used to live in {@code MdUserController} and required the
 * {@code iam.profile:update} permission. Because of that the {@code auditor} role, which the
 * specification grants no mutating action at all, could not change its own
 * password. An auditor account with {@code force_password_change = true}
 * was locked forever: the system demanded a password change and forbade it at the same time.
 *
 * The chosen fix: changing one's own password leaves the form matrix and sits
 * next to sign-in and sign-out. This removes the contradiction at the root:
 * one's own credentials are not instance data, and a form permission has nothing
 * to do with them. The auditor role definition stays intact.
 *
 * Authentication is required: the path is not in {@code PUBLIC_PATHS}, so the
 * general {@code anyRequest().authenticated()} rule covers it, and the old
 * password is checked separately in {@link MdUserSecurityService#changePassword}.
 *
 * The old path is kept as an alias: removing an endpoint is a breaking change
 * and requires {@code /api/v2}.
 */
@RestController
@RequestMapping({"/api/v1/auth", "/api/v1/iam/users/me"})
public class KauthPasswordController {

    private final MdUserSecurityService userSecurityService;

    public KauthPasswordController(MdUserSecurityService userSecurityService) {
        this.userSecurityService = userSecurityService;
    }

    @Operation(
            summary = "Change my password",
            description = "Changes the caller's password after checking the current one.")
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> changeMyPassword(@Valid @RequestBody ChangePasswordDto body) {
        var principal = SecurityContext.getPrincipal();
        if (principal == null) throw ApiException.invalidCredentials();
        userSecurityService.changePassword(
                principal.userId(), principal.authenticationVersion(), body.oldPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record ChangePasswordDto(
            @NotBlank String oldPassword,

            @NotBlank @Size(min = PasswordValidator.MIN_PASSWORD_LENGTH, max = PasswordValidator.MAX_PASSWORD_LENGTH)
            String newPassword) {}
}
