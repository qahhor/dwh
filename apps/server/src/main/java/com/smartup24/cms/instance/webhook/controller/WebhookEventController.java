package com.smartup24.cms.instance.webhook.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.webhook.api.WebhookEventItem;
import com.smartup24.cms.instance.webhook.pref.WebhookPref;
import com.smartup24.cms.instance.webhook.service.WebhookEventCatalog;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/webhooks/events}: the events a subscription may name (ADR-0032, 6.9), for its form. */
@RestController
@RequestMapping("/api/v1/webhooks/events")
public class WebhookEventController {

    private final WebhookEventCatalog catalog;

    public WebhookEventController(WebhookEventCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(
            summary = "List webhook events",
            description = "The events a webhook subscription may name, from the declared entities; any other is 422.")
    @GetMapping
    @RequiresPermission(form = WebhookPref.FORM_WEBHOOKS, action = "view")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<List<WebhookEventItem>> listEvents() {
        return ResponseEntity.ok(catalog.events());
    }
}
