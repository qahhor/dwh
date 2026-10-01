package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdSettingService;
import io.swagger.v3.oas.annotations.Operation;
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
    @Operation(summary = "Get the effective settings", description = "The settings in force for the caller.")
    @GetMapping
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<Map<String, String>> getEffectiveSettings() {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(settingService.getEffectiveSettings(userId));
    }

    /** What a signed-in session needs to know about itself: when inactivity closes it (roadmap item 28). */
    @Operation(
            summary = "Get the session settings",
            description = "What a signed-in session needs to know about itself, such as when inactivity closes it.")
    @GetMapping("/session")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<Map<String, Integer>> getSessionSettings() {
        return ResponseEntity.ok(Map.of("idleLockMinutes", settingService.idleLockMinutes()));
    }

    @Operation(summary = "Get the system settings", description = "The installation-wide settings.")
    @GetMapping("/system")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<Map<String, String>> getSystemSettings() {
        return ResponseEntity.ok(settingService.getInstanceSettings());
    }

    @Operation(summary = "Update the system settings", description = "Changes installation-wide settings.")
    @PatchMapping("/system")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> updateSystemSettings(@RequestBody Map<String, String> body) {
        settingService.updateInstanceSettings(body);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get my settings", description = "The caller's own settings.")
    @GetMapping("/user")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "view")
    public ResponseEntity<Map<String, String>> getUserSettings() {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(settingService.getUserSettings(userId));
    }

    @Operation(summary = "Update my settings", description = "Changes the caller's own settings.")
    @PatchMapping("/user")
    @RequiresPermission(form = MdPref.FORM_PROFILE, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> updateUserSettings(@RequestBody Map<String, String> body) {
        Long userId = SecurityContext.getCurrentUserId();
        settingService.updateUserSettings(userId, body);
        return ResponseEntity.noContent().build();
    }
}
