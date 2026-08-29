package com.greenwhite.dwh.cp.controller;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.security.CpSecurityContext;
import com.greenwhite.dwh.cp.service.CpAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;

/** Вход в control panel (FR-CP-7). */
@RestController
@RequestMapping("/api/v1/auth")
public class CpAuthController {

    private final CpAuthService authService;

    public CpAuthController(CpAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginDto body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        var result = authService.login(body.login(), body.password(), clientIp(request),
                request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "unknown");

        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie
                .from(CpPref.SESSION_COOKIE_NAME, result.rawToken())
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(CpPref.SESSION_TTL_DAYS))
                .build().toString());

        return ResponseEntity.ok(Map.of(
                "login", result.login(),
                "name", result.name(),
                "roles", result.roles()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var principal = CpSecurityContext.get();
        authService.logout(principal != null ? principal.sessionId() : null);

        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie
                .from(CpPref.SESSION_COOKIE_NAME, "")
                .httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/").maxAge(0)
                .build().toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        var p = CpSecurityContext.get();
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Не аутентифицирован");
        }
        return ResponseEntity.ok(Map.of("login", p.login(), "name", p.name(), "roles", p.roles()));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginDto(@NotBlank String login, @NotBlank String password) {}
}
