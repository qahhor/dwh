package com.greenwhite.dwh.instance.ms.notify.controller;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.ms.notify.pref.MsNotifyPref;
import com.greenwhite.dwh.instance.ms.notify.repository.MsAnnouncementRepository;
import com.greenwhite.dwh.instance.ms.notify.service.MsNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/announcements")
public class MsAnnouncementController {

    private final MsNotificationService notificationService;

    public MsAnnouncementController(MsNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "view")
    public ResponseEntity<List<MsAnnouncementRepository.AnnouncementRecord>> getAnnouncements(
            @RequestParam(name = "language", defaultValue = "ru") String language) {

        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("РџРѕР»СЊР·РѕРІР°С‚РµР»СЊ РЅРµ Р°РІС‚РѕСЂРёР·РѕРІР°РЅ");

        return ResponseEntity.ok(notificationService.getActiveAnnouncements(userId, language));
    }

    @PostMapping("/{id}/read")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "view")
    public ResponseEntity<Void> markAsRead(@PathVariable("id") Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("РџРѕР»СЊР·РѕРІР°С‚РµР»СЊ РЅРµ Р°РІС‚РѕСЂРёР·РѕРІР°РЅ");

        notificationService.markAnnouncementAsRead(id, userId);
        return ResponseEntity.noContent().build();
    }
}
