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
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "view")
    public ResponseEntity<Map<String, String>> getSettings() {
        Long userId = SecurityContext.getCurrentUserId();
        return ResponseEntity.ok(settingService.getEffectiveSettings(userId));
    }

    @PatchMapping
    @RequiresPermission(form = MdPref.FORM_SETTINGS, action = "update")
    public ResponseEntity<Void> updateSettings(@RequestBody Map<String, String> body) {
        settingService.updateInstanceSettings(body);
        return ResponseEntity.noContent().build();
    }
}
