package com.smartup24.cms.instance.webhook.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.Revisioned;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.workflow.EntityTransition;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookOutboxRepository outboxRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AuditLogService auditLogService;
    private final WebhookTargetPolicy targetPolicy;
    private final EntityRegistry entityRegistry;

    @Autowired
    public WebhookService(
            WebhookSubscriptionRepository subscriptionRepository,
            WebhookOutboxRepository outboxRepository,
            AuditLogService auditLogService,
            WebhookTargetPolicy targetPolicy,
            EntityRegistry entityRegistry) {
        this.subscriptionRepository = subscriptionRepository;
        this.outboxRepository = outboxRepository;
        this.auditLogService = auditLogService;
        this.targetPolicy = targetPolicy;
        this.entityRegistry = entityRegistry;
    }

    public WebhookService(
            WebhookSubscriptionRepository subscriptionRepository,
            WebhookOutboxRepository outboxRepository,
            AuditLogService auditLogService,
            WebhookTargetPolicy targetPolicy) {
        this(subscriptionRepository, outboxRepository, auditLogService, targetPolicy, new EntityRegistry(List.of()));
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

        validateSubscribedEvents(subscribedEvents);
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
        if (targetUrl != null) {
            targetPolicy.validate(targetUrl);
        }
        if (subscribedEvents != null) {
            validateSubscribedEvents(subscribedEvents);
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

    /**
     * Compute HMAC-SHA256 signature for webhook timestamp and payload (timestamp.body) for replay protection
     * (ADR-0032, question B9; plan 10/10, item 5.4).
     */
    public static String computeHmacSha256(long timestamp, String payload, String secretKey) {
        return computeHmacSha256(timestamp + "." + payload, secretKey);
    }

    /**
     * The catalog of available webhook events derived from registered entities (ADR-0032, 6.9).
     */
    public List<WebhookEventView> listEvents() {
        Map<String, WebhookEventView> events = new LinkedHashMap<>();
        events.put(
                "*",
                new WebhookEventView(
                        "*", null, null, null, "settings.webhooks.event_all", "settings.webhooks.event_all_desc"));

        for (EntityDefinition entity : entityRegistry.all()) {
            String form = entity.form();
            String entityCode = entity.code();

            registerEvent(events, form + ".created", entityCode, form, "created", null);
            registerEvent(events, form + ".updated", entityCode, form, "updated", null);
            registerEvent(events, form + ".deleted", entityCode, form, "deleted", null);

            if (entity.capabilities().contains(EntityCapability.ARCHIVE)) {
                registerEvent(events, form + ".archived", entityCode, form, "archived", null);
                registerEvent(events, form + ".restored", entityCode, form, "restored", null);
            }

            for (EntityDefinition.EntityAction action : entity.actions()) {
                String actionCode = action.code();
                if (isStandardAction(actionCode)) {
                    continue;
                }
                String nameKey = null;
                if (entity.rights() != null && entity.rights().actionKeys() != null) {
                    nameKey = entity.rights().actionKeys().get(action.permission());
                }
                registerEvent(events, form + "." + actionCode, entityCode, form, actionCode, nameKey);
            }

            // A workflow transition is published under its own code, like a record action.
            if (entity.model() != null && entity.model().workflow() != null) {
                for (EntityTransition transition : entity.model().workflow().transitions()) {
                    registerEvent(events, form + "." + transition.code(), entityCode, form, transition.code(), null);
                }
            }
        }
        return List.copyOf(events.values());
    }

    public Set<String> knownEventCodes() {
        Set<String> codes = new HashSet<>();
        for (WebhookEventView view : listEvents()) {
            codes.add(view.code());
        }
        return codes;
    }

    private void validateSubscribedEvents(List<String> subscribedEvents) {
        if (subscribedEvents == null || subscribedEvents.isEmpty()) {
            return;
        }
        Set<String> known = knownEventCodes();
        for (String event : subscribedEvents) {
            if (!known.contains(event)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "error.webhook.event_unknown", Map.of("event", event));
            }
        }
    }

    private static boolean isStandardAction(String code) {
        return "create".equals(code) || "update".equals(code) || "delete".equals(code) || "archive".equals(code);
    }

    private static void registerEvent(
            Map<String, WebhookEventView> events,
            String code,
            String entity,
            String form,
            String action,
            @Nullable String nameKey) {
        events.putIfAbsent(code, new WebhookEventView(code, entity, form, action, nameKey, null));
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

    public record WebhookEventView(
            String code,
            String event,
            @Nullable String entity,
            @Nullable String form,
            @Nullable String action,
            @Nullable String nameKey,
            @Nullable String descKey) {
        public WebhookEventView(
                String code,
                @Nullable String entity,
                @Nullable String form,
                @Nullable String action,
                @Nullable String nameKey,
                @Nullable String descKey) {
            this(code, code, entity, form, action, nameKey, descKey);
        }
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
