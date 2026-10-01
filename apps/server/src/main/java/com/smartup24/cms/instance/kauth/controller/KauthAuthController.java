package com.smartup24.cms.instance.kauth.controller;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.ClientIpResolver;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.kauth.api.LoginResponse;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.service.KauthAuthService;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.MdUserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class KauthAuthController {

    private final KauthAuthService authService;
    private final KauthSessionService sessionService;
    private final MdUserService userService;
    private final CsrfTokenRepository csrfTokenRepository;
    private final ClientIpResolver clientIpResolver;

    @Autowired
    public KauthAuthController(
            KauthAuthService authService,
            KauthSessionService sessionService,
            MdUserService userService,
            CsrfTokenRepository csrfTokenRepository,
            ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.userService = userService;
        this.csrfTokenRepository = csrfTokenRepository;
        this.clientIpResolver = clientIpResolver != null ? clientIpResolver : new ClientIpResolver(null);
    }

    public KauthAuthController(
            KauthAuthService authService,
            KauthSessionService sessionService,
            MdUserService userService,
            CsrfTokenRepository csrfTokenRepository) {
        this(authService, sessionService, userService, csrfTokenRepository, null);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginDto body, HttpServletRequest request, HttpServletResponse response) {

        String ip = clientIpResolver.resolveClientIp(request);
        String userAgent = request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "Unknown";

        var result = authService.login(body.login(), body.password(), ip, userAgent, body.deviceInfo());

        if (result.isOtpRequired()) {
            return ResponseEntity.ok(LoginResponse.otp(result.otpToken()));
        }

        setSessionCookie(request, response, result.rawSessionCookie());

        return ResponseEntity.ok(LoginResponse.success(MdUserView.from(result.user())));
    }

    @PostMapping("/otp")
    public ResponseEntity<LoginResponse> verifyOtp(
            @Valid @RequestBody OtpVerifyDto body, HttpServletRequest request, HttpServletResponse response) {

        String ip = clientIpResolver.resolveClientIp(request);
        String userAgent = request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "Unknown";

        var result = authService.verifyOtp(body.otpToken(), body.code(), ip, userAgent, body.deviceInfo());
        setSessionCookie(request, response, result.rawSessionCookie());

        return ResponseEntity.ok(LoginResponse.success(MdUserView.from(result.user())));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var principal = SecurityContext.getPrincipal();
        if (principal != null && principal.sessionId() != null) {
            sessionService.closeSession(principal.sessionId());
        }

        boolean isSecure = clientIpResolver.isSecure(request);
        ResponseCookie cookie = ResponseCookie.from(KauthPref.SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(isSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        csrfTokenRepository.saveToken(null, request, response);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("error.auth.not_signed_in");
        }

        MdUserView user = userService.getSignedInUserView(userId);
        var principal = SecurityContext.getPrincipal();
        Set<String> permissions = principal != null ? principal.effectivePermissions() : Set.of();
        long version = principal != null ? principal.permissionVersion() : 1L;

        return ResponseEntity.ok(new MeResponse(user, permissions, version));
    }

    private void setSessionCookie(HttpServletRequest request, HttpServletResponse response, String rawToken) {
        boolean isSecure = clientIpResolver.isSecure(request);
        ResponseCookie cookie = ResponseCookie.from(KauthPref.SESSION_COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(isSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(60 * 60 * 24 * 7) // 7 days
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        // Renew only after completed credential/OTP authentication, including stale-cookie relogin.
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }

    public record LoginDto(@NotBlank String login, @NotBlank String password, String deviceInfo) {}

    public record OtpVerifyDto(
            @NotBlank String otpToken, @NotBlank String code, String deviceInfo) {}

    public record MeResponse(MdUserView user, Set<String> permissions, long permissionsVersion) {}
}
