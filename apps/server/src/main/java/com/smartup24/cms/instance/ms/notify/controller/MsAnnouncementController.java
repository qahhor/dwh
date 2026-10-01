package com.smartup24.cms.instance.ms.notify.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.notify.api.AnnouncementView;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/announcements")
public class MsAnnouncementController {

    private final MsNotificationService notificationService;

    public MsAnnouncementController(MsNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(
            summary = "List active announcements",
            description = "The published announcements addressed to the caller.")
    @GetMapping({"", "/active"})
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "view")
    public ResponseEntity<List<AnnouncementView>> getAnnouncements(
            @RequestParam(name = "language", defaultValue = "ru") String language) {

        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        return ResponseEntity.ok(notificationService.getActiveAnnouncements(userId, language));
    }

    @Operation(summary = "Mark an announcement read", description = "Records that the caller has read an announcement.")
    @PostMapping("/{id}/read")
    @RequiresPermission(form = MsNotifyPref.FORM_ANNOUNCEMENTS, action = "view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> markAsRead(@PathVariable("id") Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        notificationService.markAnnouncementAsRead(id, userId);
        return ResponseEntity.noContent().build();
    }
}
