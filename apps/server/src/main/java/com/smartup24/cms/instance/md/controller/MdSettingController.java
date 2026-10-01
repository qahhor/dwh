package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.SystemSettingsView;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdSettingService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
public class MdSettingController {

    private final MdSettingService settingService;

    public MdSettingController(MdSettingService settingService) {
        this.settingService = settingService;
    }

    // Свои настройки пользователя — это часть профиля, а не администрирования
    // экземпляра: право берём от формы профиля, которая есть у всех системных
    // ролей (ТЗ-01 разд. 4.4.1). Форма platform.settings остаётся за админом.
    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<Map<String, String>> getEffectiveSettings() {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(settingService.getEffectiveSettings(userId));
    }

    /** What a signed-in session needs to know about itself: when inactivity closes it (roadmap item 28). */
    @GetMapping("/session")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<Map<String, Integer>> getSessionSettings() {
        return ResponseEntity.ok(Map.of("idleLockMinutes", settingService.idleLockMinutes()));
    }

    /** The system settings and the revision of the set (body and {@code ETag}), which a save names (ADR-0024). */
    @GetMapping("/system")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<SystemSettingsView> getSystemSettings() {
        return ResponseEntity.ok(settingService.getSystemSettings());
    }

    /** Saves the keys sent, made from the revision of the set: 428 without {@code If-Match}, 409 from an older one. */
    @PatchMapping("/system")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> updateSystemSettings(
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody Map<String, String> body) {
        long revision = settingService.updateInstanceSettings(body, Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    @GetMapping("/user")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<Map<String, String>> getUserSettings() {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(settingService.getUserSettings(userId));
    }

    @PatchMapping("/user")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> updateUserSettings(@RequestBody Map<String, String> body) {
        Long userId = SecurityContext.getCurrentUserId();
        settingService.updateUserSettings(userId, body);
        return ResponseEntity.noContent().build();
    }
}
