package com.smartup24.cms.instance.kauth.controller;

import com.smartup24.cms.instance.common.security.ClientIpResolver;
import com.smartup24.cms.instance.kauth.service.KauthPasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Password reset by a one-time link (plan 10/10, item 0.1). Public: the caller is not signed in.
 *
 * <p>A request always answers 204, known email or not; the link reaches only a confirmed email or Telegram chat.
 */
@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class KauthPasswordResetController {

    private final KauthPasswordResetService resetService;
    private final ClientIpResolver clientIpResolver;

    public KauthPasswordResetController(KauthPasswordResetService resetService, ClientIpResolver clientIpResolver) {
        this.resetService = resetService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(@Valid @RequestBody RequestDto body, HttpServletRequest request) {
        resetService.requestReset(body.email(), clientIpResolver.resolveClientIp(request), userAgent(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmDto body, HttpServletRequest request) {
        resetService.confirmReset(body.token(), body.newPassword(),
                clientIpResolver.resolveClientIp(request), userAgent(request));
        return ResponseEntity.noContent().build();
    }

    private static String userAgent(HttpServletRequest request) {
        String value = request.getHeader("User-Agent");
        return value != null ? value : "Unknown";
    }

    public record RequestDto(@NotBlank String email) {}

    public record ConfirmDto(@NotBlank String token, @NotBlank String newPassword) {}
}
