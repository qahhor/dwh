package com.smartup24.cms.instance.kauth.controller;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.kauth.api.ActiveSessionView;
import com.smartup24.cms.instance.kauth.api.SessionView;
import com.smartup24.cms.instance.kauth.api.UserSecuritySummary;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdUserSecurityService;
import com.smartup24.cms.instance.md.service.MdUserService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/iam")
public class KauthSessionController {

    private final KauthSessionService sessionService;
    private final MdUserService userService;
    private final MdUserSecurityService userSecurityService;

    public KauthSessionController(
            KauthSessionService sessionService, MdUserService userService, MdUserSecurityService userSecurityService) {
        this.sessionService = sessionService;
        this.userService = userService;
        this.userSecurityService = userSecurityService;
    }

    @Operation(summary = "List my sessions", description = "The open sessions of the caller.")
    @GetMapping({"/profile/sessions", "/sessions"})
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<List<ActiveSessionView>> listActiveSessions() {
        var principal = SecurityContext.getPrincipal();
        Long userId = principal != null ? principal.userId() : null;
        if (userId == null) {
            throw ApiException.unauthorized("error.auth.not_signed_in");
        }

        return ResponseEntity.ok(sessionService.listOwnActiveSessions(userId, principal.sessionId()));
    }

    @Operation(
            summary = "Close my other sessions",
            description = "Closes every session of the caller except the current one.")
    @DeleteMapping({"/profile/sessions/others", "/sessions/others"})
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
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

    @Operation(summary = "Close one of my sessions", description = "Closes one session of the caller.")
    @DeleteMapping({"/profile/sessions/{id}", "/sessions/{id}"})
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> closeSession(@PathVariable("id") Long id) {
        var principal = SecurityContext.getPrincipal();
        if (principal == null || principal.userId() == null) {
            throw ApiException.unauthorized("error.auth.not_signed_in");
        }
        // Only the caller's own session: the id alone once closed anyone's (IDOR).
        sessionService.closeUserSession(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "List the sessions of a user",
            description = "The open sessions of a user, for an administrator.")
    @GetMapping({"/users/{userId}/sessions", "/profile/sessions/users/{userId}"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<List<SessionView>> listUserSessions(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(sessionService.listUserActiveSessions(userId));
    }

    @Operation(summary = "Close the sessions of a user", description = "Closes every open session of a user.")
    @DeleteMapping({"/users/{userId}/sessions", "/profile/sessions/users/{userId}"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> closeAllUserSessions(@PathVariable("userId") Long userId) {
        sessionService.closeAllUserSessions(userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Close a session of a user", description = "Closes one session of a user.")
    @DeleteMapping({"/users/{userId}/sessions/{id}", "/profile/sessions/users/{userId}/{id}"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> closeUserSession(@PathVariable("userId") Long userId, @PathVariable("id") Long id) {
        sessionService.closeUserSession(userId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Get the security summary of a user",
            description = "The security summary of a user, for an administrator.")
    @GetMapping({"/users/{userId}/security", "/profile/sessions/users/{userId}/security"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<UserSecuritySummary> getUserSecuritySummary(@PathVariable("userId") Long userId) {
        var user = userService.findAuthUserById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return ResponseEntity.ok(sessionService.getUserSecuritySummary(userId, user));
    }

    @Operation(
            summary = "Force a password change",
            description = "Makes the user change the password at the next sign-in.")
    @PostMapping({"/users/{userId}/force-password-change", "/profile/sessions/users/{userId}/force-password-change"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> forcePasswordChange(@PathVariable("userId") Long userId) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        userSecurityService.setForcePasswordChange(userId, true, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reset the second factor", description = "Resets the second factor of a user.")
    @PostMapping({"/users/{userId}/reset-2fa", "/profile/sessions/users/{userId}/reset-2fa"})
    @RequiresPermission(form = MdPref.FORM_USERS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> reset2fa(@PathVariable("userId") Long userId) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        userSecurityService.reset2fa(userId, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
