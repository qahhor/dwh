package com.smartup24.cms.instance.kwh.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.annotation.ReturnsSecret;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.kwh.pref.KwhPref;
import com.smartup24.cms.instance.kwh.service.KwhWebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    @ReturnsSecret
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<KwhWebhookService.CreatedSubscription> createSubscription(
            @Valid @RequestBody CreateSubscriptionDto body) {

        Long currentUserId = SecurityContext.getCurrentUserId();
        var sub = webhookService.createSubscription(
                body.name(), body.targetUrl(), body.subscribedEvents(), currentUserId);
        return Created.at("/api/v1/webhooks/subscriptions/{id}", sub.id(), sub);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = KwhPref.FORM_WEBHOOKS, action = "manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> updateSubscription(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateSubscriptionDto body) {
        long revision = webhookService.updateSubscription(
                id, body.name(), body.targetUrl(), body.subscribedEvents(), body.state(), Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = KwhPref.FORM_WEBHOOKS, action = "manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteSubscription(@PathVariable("id") Long id) {
        webhookService.deleteSubscription(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateSubscriptionDto(
            @NotBlank String name,
            @NotBlank String targetUrl,
            @NotEmpty List<String> subscribedEvents) {}

    public record UpdateSubscriptionDto(String name, String targetUrl, List<String> subscribedEvents, String state) {}
}
