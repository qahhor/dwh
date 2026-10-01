package com.smartup24.cms.instance.ms.notify.sse;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent event stream for realtime notifications (FR-NOTIF-2, FR-API-5).
 * Client: `new EventSource('/api/v1/events', {withCredentials: true})`.
 * Reconnecting after a drop is standard EventSource behaviour; no extra logic is needed.
 */
@RestController
@RequestMapping("/api/v1/events")
public class MsSseController {

    private final MsSseRegistry registry;

    public MsSseController(MsSseRegistry registry) {
        this.registry = registry;
    }

    @Operation(
            summary = "Subscribe to events",
            description = "A stream of server-sent events for the caller, kept open by the client.")
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission(form = MsNotifyPref.FORM_INBOX, action = "view")
    public SseEmitter stream() {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) {
            throw ApiException.unauthorized("error.notify.sse_auth_required");
        }
        return registry.subscribe(userId);
    }
}
