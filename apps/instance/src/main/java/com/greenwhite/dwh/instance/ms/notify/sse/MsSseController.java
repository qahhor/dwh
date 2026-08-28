package com.greenwhite.dwh.instance.ms.notify.sse;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.ms.notify.pref.MsNotifyPref;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Поток серверных событий для realtime-уведомлений (FR-NOTIF-2, FR-API-5).
 * Клиент: `new EventSource('/api/v1/events', {withCredentials: true})`.
 * Переподключение при разрыве — штатное поведение EventSource, отдельной логики не нужно.
 */
@RestController
@RequestMapping("/api/v1/events")
public class MsSseController {

    private final MsSseRegistry registry;

    public MsSseController(MsSseRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public SseEmitter stream() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("Требуется авторизация для подписки на события");
        }
        return registry.subscribe(userId);
    }
}
