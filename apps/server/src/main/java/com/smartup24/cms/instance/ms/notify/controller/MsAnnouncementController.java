package com.smartup24.cms.instance.ms.notify.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.repository.MsAnnouncementRepository;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/announcements")
public class MsAnnouncementController {

    private final MsNotificationService notificationService;

    public MsAnnouncementController(MsNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping({"", "/active"})
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "view")
    public ResponseEntity<List<MsAnnouncementRepository.AnnouncementRecord>> getAnnouncements(
            @RequestParam(name = "language", defaultValue = "ru") String language) {

        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("Пользователь не авторизован");

        return ResponseEntity.ok(notificationService.getActiveAnnouncements(userId, language));
    }

    @PostMapping("/{id}/read")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "view")
    public ResponseEntity<Void> markAsRead(@PathVariable("id") Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("Пользователь не авторизован");

        notificationService.markAnnouncementAsRead(id, userId);
        return ResponseEntity.noContent().build();
    }
}
