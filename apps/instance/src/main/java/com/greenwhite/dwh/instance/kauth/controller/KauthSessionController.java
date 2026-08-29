package com.greenwhite.dwh.instance.kauth.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.kauth.repository.KauthSessionRepository;
import com.greenwhite.dwh.instance.kauth.service.KauthSessionService;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/iam/profile/sessions")
public class KauthSessionController {

    private final KauthSessionService sessionService;

    public KauthSessionController(KauthSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<List<KauthSessionRepository.SessionRecord>> listActiveSessions() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("Пользователь не авторизован");
        }

        return ResponseEntity.ok(sessionService.getUserActiveSessions(userId));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    public ResponseEntity<Void> closeSession(@PathVariable("id") Long id) {
        sessionService.closeSession(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "view")
    public ResponseEntity<List<KauthSessionRepository.SessionRecord>> listUserSessions(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(sessionService.getUserActiveSessions(userId));
    }

    @DeleteMapping("/users/{userId}")
    @RequiresPermission(form = MdPref.FORM_USERS, action = "block")
    public ResponseEntity<Void> closeAllUserSessions(@PathVariable("userId") Long userId) {
        sessionService.closeAllUserSessions(userId);
        return ResponseEntity.noContent().build();
    }
}

