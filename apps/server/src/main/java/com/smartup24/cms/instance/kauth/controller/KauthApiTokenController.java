package com.smartup24.cms.instance.kauth.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.annotation.ReturnsSecret;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.kauth.api.ApiTokenView;
import com.smartup24.cms.instance.kauth.api.CreateTokenRequest;
import com.smartup24.cms.instance.kauth.api.CreatedApiToken;
import com.smartup24.cms.instance.kauth.service.KauthApiTokenService;
import com.smartup24.cms.instance.md.api.MdPref;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/iam/profile/tokens")
public class KauthApiTokenController {

    private final KauthApiTokenService apiTokenService;

    public KauthApiTokenController(KauthApiTokenService apiTokenService) {
        this.apiTokenService = apiTokenService;
    }

    @Operation(
            summary = "List my API tokens",
            description = "The personal API tokens of the caller, without their secrets.")
    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_tokens")
    public ResponseEntity<List<ApiTokenView>> listTokens() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("error.auth.not_signed_in");
        }

        return ResponseEntity.ok(apiTokenService.getUserTokens(userId));
    }

    @Operation(
            summary = "Create an API token",
            description = "Issues a personal API token; its secret is returned once and never stored for replay.")
    @PostMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_tokens")
    @ReturnsSecret
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<CreatedApiToken> createToken(@Valid @RequestBody CreateTokenRequest body) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("error.auth.not_signed_in");
        }

        var result = apiTokenService.createToken(SecurityContext.getPrincipal(), body.name(), body.expiresAt());
        return Created.at("/api/v1/iam/profile/tokens/{id}", result.record().id(), result);
    }

    @Operation(summary = "Revoke an API token", description = "Revokes one personal API token of the caller.")
    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "manage_tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> revokeToken(@PathVariable("id") Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("error.auth.not_signed_in");
        }

        apiTokenService.revokeToken(id, userId);
        return ResponseEntity.noContent().build();
    }
}
