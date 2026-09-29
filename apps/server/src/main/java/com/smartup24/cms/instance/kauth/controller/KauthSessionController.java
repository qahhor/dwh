package com.smartup24.cms.instance.kauth.controller;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import com.smartup24.cms.instance.kauth.service.UserSecuritySummary;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/iam")
public class KauthSessionController {

    private final KauthSessionService sessionService;
    private final MdUserService userService;

    public KauthSessionController(KauthSessionService sessionService, MdUserService userService) {
        this.sessionService = sessionService;
        this.userService = userService;
    }

    @GetMapping({"/profile/sessions", "/sessions"})
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<List<ActiveSessionDto>> listActiveSessions() {
        var principal = SecurityContext.getPrincipal();
        Long userId = principal != null ? principal.userId() : null;
        if (userId == null) {
            throw ApiException.unauthorized("error.auth.not_signed_in");
        }

        Long currentSessionId = principal.sessionId();
        List<ActiveSessionDto> list = sessionService.getUserActiveSessions(userId).stream()
                .map(s -> ActiveSessionDto.from(s, currentSessionId))
                .toList();
        return ResponseEntity.ok(list);
    }

    @DeleteMapping({"/profile/sessions/others", "/sessions/others"})
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    public ResponseEntity<Void> closeOtherSessions() {
        var principal = SecurityContext.getPrincipal();
        if (principal == null || principal.userId() == null) {
            throw ApiException.unauthorized("error.auth.not_signed_in");
        }
        if (principal.sessionId() != null) {
            sessionService.closeOtherSessions(principal.userId(), principal.sessionId());
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping({"/profile/sessions/{id}", "/sessions/{id}"})
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    public ResponseEntity<Void> closeSession(@PathVariable("id") Long id) {
        sessionService.closeSession(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping({"/users/{userId}/sessions", "/profile/sessions/users/{userId}"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<List<KauthSessionRepository.SessionRecord>> listUserSessions(
            @PathVariable("userId") Long userId) {
        return ResponseEntity.ok(sessionService.getUserActiveSessions(userId));
    }

    @DeleteMapping({"/users/{userId}/sessions", "/profile/sessions/users/{userId}"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "block")
    public ResponseEntity<Void> closeAllUserSessions(@PathVariable("userId") Long userId) {
        sessionService.closeAllUserSessions(userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping({"/users/{userId}/sessions/{id}", "/profile/sessions/users/{userId}/{id}"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "block")
    public ResponseEntity<Void> closeUserSession(@PathVariable("userId") Long userId, @PathVariable("id") Long id) {
        sessionService.closeSession(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping({"/users/{userId}/security", "/profile/sessions/users/{userId}/security"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<UserSecuritySummary> getUserSecuritySummary(@PathVariable("userId") Long userId) {
        var user = userService.findAuthUserById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return ResponseEntity.ok(sessionService.getUserSecuritySummary(userId, user));
    }

    @PostMapping({"/users/{userId}/force-password-change", "/profile/sessions/users/{userId}/force-password-change"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "update")
    public ResponseEntity<Void> forcePasswordChange(@PathVariable("userId") Long userId) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        userService.setForcePasswordChange(userId, true, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping({"/users/{userId}/reset-2fa", "/profile/sessions/users/{userId}/reset-2fa"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "update")
    public ResponseEntity<Void> reset2fa(@PathVariable("userId") Long userId) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        userService.reset2fa(userId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    public record ActiveSessionDto(
            Long id,
            Long userId,
            String ip,
            String userAgent,
            String deviceInfo,
            Instant createdAt,
            Instant lastSeenAt,
            Instant closedAt,
            boolean current) {
        public static ActiveSessionDto from(KauthSessionRepository.SessionRecord record, Long currentSessionId) {
            return new ActiveSessionDto(
                    record.id(),
                    record.userId(),
                    record.ip(),
                    record.userAgent(),
                    record.deviceInfo(),
                    record.createdAt(),
                    record.lastSeenAt(),
                    record.closedAt(),
                    currentSessionId != null && currentSessionId.equals(record.id()));
        }
    }
}
