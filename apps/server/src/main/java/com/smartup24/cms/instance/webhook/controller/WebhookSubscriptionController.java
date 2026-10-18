package com.smartup24.cms.instance.webhook.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.annotation.ReturnsSecret;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.webhook.api.WebhookDtos.CreateSubscriptionDto;
import com.smartup24.cms.instance.webhook.api.WebhookDtos.CreatedSubscription;
import com.smartup24.cms.instance.webhook.api.WebhookDtos.SubscriptionView;
import com.smartup24.cms.instance.webhook.api.WebhookDtos.UpdateSubscriptionDto;
import com.smartup24.cms.instance.webhook.pref.WebhookPref;
import com.smartup24.cms.instance.webhook.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/subscriptions")
public class WebhookSubscriptionController {

    private final WebhookService webhookService;

    public WebhookSubscriptionController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @Operation(summary = "List webhook subscriptions", description = "The webhook subscriptions of the installation.")
    @GetMapping
    @RequiresPermission(form = WebhookPref.FORM_WEBHOOKS, action = "view")
    public ResponseEntity<List<SubscriptionView>> listSubscriptions() {
        return ResponseEntity.ok(webhookService.listSubscriptions());
    }

    @Operation(
            summary = "Create a webhook subscription",
            description = "Subscribes a target URL to events, for every record or only the records its creator may read"
                    + " (scope, creator by default); the signing key is returned once and never stored for"
                    + " replay.")
    @PostMapping
    @RequiresPermission(form = WebhookPref.FORM_WEBHOOKS, action = "manage")
    @ReturnsSecret
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<CreatedSubscription> createSubscription(@Valid @RequestBody CreateSubscriptionDto body) {

        Long currentUserId = SecurityContext.getCurrentUserId();
        var sub = webhookService.createSubscription(
                body.name(), body.targetUrl(), body.subscribedEvents(), body.scope(), currentUserId);
        return Created.at("/api/v1/webhooks/subscriptions/{id}", sub.id(), sub);
    }

    @Operation(
            summary = "Update a webhook subscription",
            description = "Changes the name, target, events, state or scope of a subscription.")
    @PatchMapping("/{id}")
    @RequiresPermission(form = WebhookPref.FORM_WEBHOOKS, action = "manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> updateSubscription(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateSubscriptionDto body) {
        long revision = webhookService.updateSubscription(
                id,
                body.name(),
                body.targetUrl(),
                body.subscribedEvents(),
                body.state(),
                body.scope(),
                Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    @Operation(summary = "Delete a webhook subscription", description = "Removes a webhook subscription.")
    @DeleteMapping("/{id}")
    @RequiresPermission(form = WebhookPref.FORM_WEBHOOKS, action = "manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteSubscription(@PathVariable("id") Long id) {
        webhookService.deleteSubscription(id);
        return ResponseEntity.noContent().build();
    }
}
