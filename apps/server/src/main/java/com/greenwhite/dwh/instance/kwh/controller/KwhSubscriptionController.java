package com.greenwhite.dwh.instance.kwh.controller;

import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.kwh.service.KwhWebhookService;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.kwh.pref.KwhPref;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/webhooks/subscriptions")
public class KwhSubscriptionController {

    private final KwhWebhookService webhookService;

    public KwhSubscriptionController(KwhWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @GetMapping
    @RequiresPermission(form = KwhPref.FORM_WEBHOOKS, action = "view")
    public ResponseEntity<List<KwhWebhookService.SubscriptionView>> listSubscriptions() {
        return ResponseEntity.ok(webhookService.listSubscriptions());
    }

    @PostMapping
    @RequiresPermission(form = KwhPref.FORM_WEBHOOKS, action = "manage")
    public ResponseEntity<KwhWebhookService.CreatedSubscription> createSubscription(
            @Valid @RequestBody CreateSubscriptionDto body) {

        Long currentUserId = SecurityContext.getCurrentUserId();
        var sub = webhookService.createSubscription(body.name(), body.targetUrl(), body.subscribedEvents(), currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(sub);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = KwhPref.FORM_WEBHOOKS, action = "manage")
    public ResponseEntity<Void> updateSubscription(
            @PathVariable("id") Long id,
            @RequestBody UpdateSubscriptionDto body) {

        webhookService.updateSubscription(id, body.name(), body.targetUrl(), body.subscribedEvents(), body.state());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = KwhPref.FORM_WEBHOOKS, action = "manage")
    public ResponseEntity<Void> deleteSubscription(@PathVariable("id") Long id) {
        webhookService.deleteSubscription(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateSubscriptionDto(
            @NotBlank String name,
            @NotBlank String targetUrl,
            @NotEmpty List<String> subscribedEvents
    ) {}

    public record UpdateSubscriptionDto(
            String name,
            String targetUrl,
            List<String> subscribedEvents,
            String state
    ) {}
}
