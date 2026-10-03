package com.smartup24.cms.instance.webhook.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.Revisioned;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhook subscriptions and the outbox of their events (ADR-0032, 6.9). A delivery is signed with the subscription's
 * key over {@code <timestamp>.<body>} ({@link #signature}): {@value #SIGNATURE_HEADER} carries the hex HMAC-SHA256,
 * {@value #TIMESTAMP_HEADER} the Unix seconds it covers, so a receiver that checks both refuses a replayed delivery.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** The header of the signature: hex HMAC-SHA256 of {@code <timestamp>.<body>} with the subscription's key. */
    public static final String SIGNATURE_HEADER = "X-Signature-SHA256";

    /** The header of the moment the signature covers, Unix seconds. */
    public static final String TIMESTAMP_HEADER = "X-Signature-Timestamp";

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookOutboxRepository outboxRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AuditLogService auditLogService;
    private final WebhookTargetPolicy targetPolicy;
    private final WebhookEventCatalog events;

    public WebhookService(
            WebhookSubscriptionRepository subscriptionRepository,
            WebhookOutboxRepository outboxRepository,
            AuditLogService auditLogService,
            WebhookTargetPolicy targetPolicy,
            WebhookEventCatalog events) {
        this.subscriptionRepository = subscriptionRepository;
        this.outboxRepository = outboxRepository;
        this.auditLogService = auditLogService;
        this.targetPolicy = targetPolicy;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<SubscriptionView> listSubscriptions() {
        return subscriptionRepository.listSubscriptions().stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public CreatedSubscription createSubscription(
            String name, String targetUrl, List<String> subscribedEvents, Long createdBy) {

        // The events first: a list naming an unknown one is refused before the target is resolved.
        events.requireKnown(subscribedEvents);
        var validatedTarget = targetPolicy.validate(targetUrl);

        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        String secretToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        var subscription = subscriptionRepository.create(name, targetUrl, secretToken, subscribedEvents, createdBy);

        // A subscription is a channel for data to leak outside, so its creation, address
        // change and deletion must be in the audit log (FR-AUD-1).
        // The secret token does NOT go to the log: more people read the audit
        // than should know the signing key.
        auditLogService.logChange(
                "kwh_subscriptions",
                String.valueOf(subscription.id()),
                "I",
                List.of("name", "target_url", "subscribed_events"),
                null,
                Map.of(
                        "name",
                        name,
                        "target_url",
                        targetPolicy.redact(validatedTarget),
                        "subscribed_events",
                        subscribedEvents != null ? subscribedEvents : List.of()));

        return new CreatedSubscription(
                subscription.id(),
                subscription.name(),
                targetPolicy.redact(validatedTarget),
                subscription.secretToken(),
                subscription.subscribedEvents(),
                subscription.state(),
                subscription.createdAt(),
                subscription.createdBy());
    }

    @Transactional
    public long updateSubscription(
            Long id,
            String name,
            String targetUrl,
            List<String> subscribedEvents,
            String state,
            long expectedRevision) {
        events.requireKnown(subscribedEvents);
        if (targetUrl != null) {
            targetPolicy.validate(targetUrl);
        }
        var before = requireSubscription(id);
        long revision = subscriptionRepository.update(id, name, targetUrl, subscribedEvents, state, expectedRevision);

        auditLogService.logChange(
                "kwh_subscriptions",
                String.valueOf(id),
                "U",
                List.of("name", "target_url", "subscribed_events", "state"),
                Map.of("name", before.name(), "target_url", redact(before.targetUrl()), "state", before.state()),
                Map.of(
                        "name",
                        name != null ? name : before.name(),
                        "target_url",
                        redact(targetUrl != null ? targetUrl : before.targetUrl()),
                        "state",
                        state != null ? state : before.state()));
        return revision;
    }

    @Transactional
    public void deleteSubscription(Long id) {
        var before = requireSubscription(id);
        subscriptionRepository.delete(id);

        auditLogService.logChange(
                "kwh_subscriptions",
                String.valueOf(id),
                "D",
                List.of("name", "target_url"),
                Map.of("name", before.name(), "target_url", redact(before.targetUrl())),
                null);
    }

    private WebhookSubscriptionRepository.SubscriptionRecord requireSubscription(Long id) {
        return subscriptionRepository
                .findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.webhook.subscription_not_found"));
    }

    @Transactional
    public void publishEvent(String eventType, Map<String, Object> payload) {
        publishEvent(eventType, () -> payload);
    }

    /**
     * Writes the event for every active subscription to it, in the caller's transaction (ADR-0032, 6.9); the payload
     * is built only when someone is subscribed.
     */
    @Transactional
    public void publishEvent(String eventType, Supplier<Map<String, Object>> payload) {
        List<WebhookSubscriptionRepository.SubscriptionRecord> active =
                subscriptionRepository.findActiveByEvent(eventType);
        if (active.isEmpty()) return;
        Map<String, Object> body = payload.get();
        for (var sub : active) {
            outboxRepository.enqueue(sub.id(), eventType, body);
        }
    }

    /**
     * The signature of a delivery: hex HMAC-SHA256 of {@code <timestamp>.<body>} with the subscription's key, the
     * timestamp in Unix seconds as sent in {@value #TIMESTAMP_HEADER}. A receiver recomputes it over the raw body and
     * refuses a timestamp older than it tolerates.
     */
    public static String signature(long timestamp, String body, String secretKey) {
        return computeHmacSha256(timestamp + "." + body, secretKey);
    }

    /**
     * Compute HMAC-SHA256 signature for webhook payload.
     */
    public static String computeHmacSha256(String payload, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }

    private String redact(String url) {
        if (url == null) {
            return "invalid-webhook-target";
        }
        try {
            return targetPolicy.redact(java.net.URI.create(url));
        } catch (IllegalArgumentException exception) {
            log.debug("webhook_target_unreadable error={}", exception.toString());
            return "invalid-webhook-target";
        }
    }

    private SubscriptionView toView(WebhookSubscriptionRepository.SubscriptionRecord subscription) {
        return new SubscriptionView(
                subscription.id(),
                subscription.name(),
                redact(subscription.targetUrl()),
                subscription.subscribedEvents(),
                subscription.state(),
                subscription.createdAt(),
                subscription.createdBy(),
                subscription.revision());
    }

    public record SubscriptionView(
            Long id,
            String name,
            String targetUrl,
            List<String> subscribedEvents,
            String state,
            Instant createdAt,
            Long createdBy,
            long revision)
            implements Revisioned {}

    public record CreatedSubscription(
            Long id,
            String name,
            String targetUrl,
            String secretToken,
            List<String> subscribedEvents,
            String state,
            Instant createdAt,
            Long createdBy) {}
}
