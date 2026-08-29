package com.greenwhite.dwh.instance.md.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.service.MdSettingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings")
public class MdSettingController {

    private final MdSettingService settingService;

    public MdSettingController(MdSettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getEffectiveSettings() {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(settingService.getEffectiveSettings(userId));
    }

    @GetMapping("/system")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<Map<String, String>> getSystemSettings() {
        return ResponseEntity.ok(settingService.getInstanceSettings());
    }

    @PatchMapping("/system")
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    public ResponseEntity<Void> updateSystemSettings(@RequestBody Map<String, String> body) {
        settingService.updateInstanceSettings(body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/user")
    public ResponseEntity<Map<String, String>> getUserSettings() {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(settingService.getUserSettings(userId));
    }

    @PatchMapping("/user")
    public ResponseEntity<Void> updateUserSettings(@RequestBody Map<String, String> body) {
        Long userId = SecurityContext.getCurrentUserId();
        settingService.updateUserSettings(userId, body);
        return ResponseEntity.noContent().build();
    }
}

