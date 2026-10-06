package com.smartup24.cms.instance.webhook.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.webhook.pref.WebhookPref;
import com.smartup24.cms.instance.webhook.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalog of webhook events (ADR-0032, 6.9; plan 10/10, item 5.4).
 */
@RestController
@RequestMapping("/api/v1/webhooks/events")
public class WebhookEventController {

    private final WebhookService webhookService;

    public WebhookEventController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @Operation(
            summary = "List webhook event catalog",
            description = "The available events that can be subscribed to, derived from the registered entities.")
    @GetMapping
    @RequiresPermission(form = WebhookPref.FORM_WEBHOOKS, action = "view")
    public ResponseEntity<List<WebhookService.WebhookEventView>> listEvents() {
        return ResponseEntity.ok(webhookService.listEvents());
    }
}
