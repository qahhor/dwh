package com.smartup24.cms.instance.ms.notify.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.notify.api.NotificationPrefUpdate;
import com.smartup24.cms.instance.ms.notify.api.NotificationPrefView;
import com.smartup24.cms.instance.ms.notify.api.NotificationView;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/notify", "/api/v1/notifications"})
public class MsNotificationController {

    static final int INBOX_PAGE = 50;
    static final int INBOX_MAX = 100;

    private final MsNotificationService notificationService;

    public MsNotificationController(MsNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "List my notifications", description = "The caller's notification inbox.")
    @GetMapping("/inbox")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public ResponseEntity<KeysetPage<NotificationView>> getInbox(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {

        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        // Pages newest first (plan 10/10, item 3.5): the inbox grows with every event, it is never sent whole.
        return ResponseEntity.ok(
                notificationService.getUserNotifications(userId, TimePage.of(limit, cursor, INBOX_PAGE, INBOX_MAX)));
    }

    @Operation(
            summary = "Count my unread notifications",
            description = "How many notifications of the caller are unread.")
    @GetMapping("/unread-count")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public ResponseEntity<Map<String, Integer>> getUnreadCount() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        int count = notificationService.getUnreadCount(userId);
        return ResponseEntity.ok(Map.of("unread_count", count));
    }

    @Operation(summary = "Mark a notification read", description = "Marks one notification of the caller as read.")
    @PostMapping("/inbox/{id}/read")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> markAsRead(@PathVariable("id") Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        notificationService.markAsRead(id, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark all notifications read", description = "Marks every notification of the caller as read.")
    @PostMapping("/inbox/read-all")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> markAllAsRead() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        notificationService.markAllAsRead(userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get my notification preferences", description = "The caller's notification preferences.")
    @GetMapping("/preferences")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public ResponseEntity<List<NotificationPrefView>> getPreferences() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        return ResponseEntity.ok(notificationService.getUserPreferences(userId));
    }

    @Operation(
            summary = "Update my notification preferences",
            description = "Replaces the caller's notification preferences.")
    @PutMapping("/preferences")
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> updatePreferences(@RequestBody List<NotificationPrefUpdate> updates) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.notify.not_authenticated");

        notificationService.updateUserPreferences(userId, updates);
        return ResponseEntity.noContent().build();
    }
}
