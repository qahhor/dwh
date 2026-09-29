package com.smartup24.cms.instance.ms.notify.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.notify.api.NotificationPrefUpdate;
import com.smartup24.cms.instance.ms.notify.api.NotificationPrefView;
import com.smartup24.cms.instance.ms.notify.api.NotificationView;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/notify", "/api/v1/notifications"})
public class MsNotificationController {

    private final MsNotificationService notificationService;

    public MsNotificationController(MsNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/inbox")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public ResponseEntity<List<NotificationView>> getInbox(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {

        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        return ResponseEntity.ok(notificationService.getUserNotifications(userId, limit));
    }

    @GetMapping("/unread-count")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public ResponseEntity<Map<String, Integer>> getUnreadCount() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        int count = notificationService.getUnreadCount(userId);
        return ResponseEntity.ok(Map.of("unread_count", count));
    }

    @PostMapping("/inbox/{id}/read")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public ResponseEntity<Void> markAsRead(@PathVariable("id") Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        notificationService.markAsRead(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/inbox/read-all")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public ResponseEntity<Void> markAllAsRead() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        notificationService.markAllAsRead(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public ResponseEntity<List<NotificationPrefView>> getPreferences() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        return ResponseEntity.ok(notificationService.getUserPreferences(userId));
    }

    @PutMapping("/preferences")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public ResponseEntity<Void> updatePreferences(@RequestBody List<NotificationPrefUpdate> updates) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        notificationService.updateUserPreferences(userId, updates);
        return ResponseEntity.noContent().build();
    }
}
