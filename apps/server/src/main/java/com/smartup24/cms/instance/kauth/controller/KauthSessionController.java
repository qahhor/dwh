package com.smartup24.cms.instance.kauth.controller;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.kauth.api.ActiveSessionView;
import com.smartup24.cms.instance.kauth.api.SessionView;
import com.smartup24.cms.instance.kauth.api.UserSecuritySummary;
import com.smartup24.cms.instance.kauth.service.KauthSessionService;
import com.smartup24.cms.instance.md.api.MdPref;
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

    public KauthSessionController(KauthSessionService sessionService, MdUserService userService) {
        this.sessionService = sessionService;
        this.userService = userService;
    }

    @Operation(summary = "List my sessions", description = "The open sessions of the caller.")
    @GetMapping("/profile/sessions")
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
    @DeleteMapping("/profile/sessions/others")
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
    @DeleteMapping("/profile/sessions/{id}")
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
    @GetMapping("/users/{userId}/sessions")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<List<SessionView>> listUserSessions(@PathVariable("userId") Long userId) {
        userService.requireVisible(SecurityContext.getCurrentUserId(), userId);
        return ResponseEntity.ok(sessionService.listUserActiveSessions(userId));
    }

    @Operation(summary = "Close the sessions of a user", description = "Closes every open session of a user.")
    @DeleteMapping("/users/{userId}/sessions")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> closeAllUserSessions(@PathVariable("userId") Long userId) {
        userService.requireVisible(SecurityContext.getCurrentUserId(), userId);
        sessionService.closeAllUserSessions(userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Close a session of a user", description = "Closes one session of a user.")
    @DeleteMapping("/users/{userId}/sessions/{id}")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> closeUserSession(@PathVariable("userId") Long userId, @PathVariable("id") Long id) {
        userService.requireVisible(SecurityContext.getCurrentUserId(), userId);
        sessionService.closeUserSession(userId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Get the security summary of a user",
            description = "The security summary of a user, for an administrator.")
    @GetMapping("/users/{userId}/security")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<UserSecuritySummary> getUserSecuritySummary(@PathVariable("userId") Long userId) {
        userService.requireVisible(SecurityContext.getCurrentUserId(), userId);
        var user = userService.findAuthUserById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return ResponseEntity.ok(sessionService.getUserSecuritySummary(userId, user));
    }
}
