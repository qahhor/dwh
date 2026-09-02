package com.greenwhite.dwh.instance.kauth.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.kauth.repository.KauthApiTokenRepository;
import com.greenwhite.dwh.instance.kauth.service.KauthApiTokenService;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/iam/profile/tokens")
public class KauthApiTokenController {

    private final KauthApiTokenService apiTokenService;

    public KauthApiTokenController(KauthApiTokenService apiTokenService) {
        this.apiTokenService = apiTokenService;
    }

    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_tokens")
    public ResponseEntity<List<KauthApiTokenRepository.ApiTokenRecord>> listTokens() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("Пользователь не авторизован");
        }

        return ResponseEntity.ok(apiTokenService.getUserTokens(userId));
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_tokens")
    public ResponseEntity<KauthApiTokenService.CreatedTokenResult> createToken(@Valid @RequestBody CreateTokenDto body) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("Пользователь не авторизован");
        }

        var result = apiTokenService.createToken(userId, body.name(), body.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_tokens")
    public ResponseEntity<Void> revokeToken(@PathVariable("id") Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("Пользователь не авторизован");
        }

        apiTokenService.revokeToken(id, userId);
        return ResponseEntity.noContent().build();
    }

    public record CreateTokenDto(
            @NotBlank String name,
            Instant expiresAt
    ) {}
}
