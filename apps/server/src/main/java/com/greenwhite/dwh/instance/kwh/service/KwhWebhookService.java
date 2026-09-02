package com.greenwhite.dwh.instance.kwh.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.kwh.repository.KwhOutboxRepository;
import com.greenwhite.dwh.instance.kwh.repository.KwhSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class KwhWebhookService {

    private final KwhSubscriptionRepository subscriptionRepository;
    private final KwhOutboxRepository outboxRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AuditLogService auditLogService;
    private final WebhookTargetPolicy targetPolicy;

    public KwhWebhookService(KwhSubscriptionRepository subscriptionRepository,
                             KwhOutboxRepository outboxRepository,
                             AuditLogService auditLogService,
                             WebhookTargetPolicy targetPolicy) {
        this.subscriptionRepository = subscriptionRepository;
        this.outboxRepository = outboxRepository;
        this.auditLogService = auditLogService;
        this.targetPolicy = targetPolicy;
    }

    @Transactional(readOnly = true)
    public List<SubscriptionView> listSubscriptions() {
        return subscriptionRepository.listSubscriptions().stream().map(this::toView).toList();
    }

    @Transactional
    public CreatedSubscription createSubscription(
            String name, String targetUrl, List<String> subscribedEvents, Long createdBy) {

        var validatedTarget = targetPolicy.validate(targetUrl);

        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        String secretToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        var subscription = subscriptionRepository.create(name, targetUrl, secretToken, subscribedEvents, createdBy);

        // Подписка — канал утечки данных наружу, поэтому её появление, смена
        // адреса и удаление обязаны быть в журнале (FR-AUD-1).
        // Секретный токен в журнал НЕ попадает: аудит читают больше людей,
        // чем должны знать ключ подписи.
        auditLogService.logChange("kwh_subscriptions", String.valueOf(subscription.id()), "I",
                List.of("name", "target_url", "subscribed_events"),
                null,
                Map.of("name", name, "target_url", targetPolicy.redact(validatedTarget),
                        "subscribed_events", subscribedEvents != null ? subscribedEvents : List.of()));

        return new CreatedSubscription(
                subscription.id(), subscription.name(), targetPolicy.redact(validatedTarget),
                subscription.secretToken(), subscription.subscribedEvents(), subscription.state(),
                subscription.createdAt(), subscription.createdBy());
    }

    @Transactional
    public void updateSubscription(Long id, String name, String targetUrl, List<String> subscribedEvents, String state) {
        if (targetUrl != null) {
            targetPolicy.validate(targetUrl);
        }
        var before = requireSubscription(id);
        subscriptionRepository.update(id, name, targetUrl, subscribedEvents, state);

        auditLogService.logChange("kwh_subscriptions", String.valueOf(id), "U",
                List.of("name", "target_url", "subscribed_events", "state"),
                Map.of("name", before.name(), "target_url", redact(before.targetUrl()), "state", before.state()),
                Map.of("name", name != null ? name : before.name(),
                        "target_url", redact(targetUrl != null ? targetUrl : before.targetUrl()),
                        "state", state != null ? state : before.state()));
    }

    @Transactional
    public void deleteSubscription(Long id) {
        var before = requireSubscription(id);
        subscriptionRepository.delete(id);

        auditLogService.logChange("kwh_subscriptions", String.valueOf(id), "D",
                List.of("name", "target_url"),
                Map.of("name", before.name(), "target_url", redact(before.targetUrl())),
                null);
    }

    private KwhSubscriptionRepository.SubscriptionRecord requireSubscription(Long id) {
        return subscriptionRepository.findById(id).orElseThrow(() ->
                ApiException.notFound(ErrorCode.NOT_FOUND, "Подписка на события не найдена"));
    }

    @Transactional
    public void publishEvent(String eventType, Map<String, Object> payload) {
        List<KwhSubscriptionRepository.SubscriptionRecord> active = subscriptionRepository.findActiveByEvent(eventType);
        for (var sub : active) {
            outboxRepository.enqueue(sub.id(), eventType, payload);
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

    private String redact(String url) {
        try {
            return targetPolicy.redact(java.net.URI.create(url));
        } catch (RuntimeException exception) {
            return "invalid-webhook-target";
        }
    }

    private SubscriptionView toView(KwhSubscriptionRepository.SubscriptionRecord subscription) {
        return new SubscriptionView(
                subscription.id(), subscription.name(), redact(subscription.targetUrl()),
                subscription.subscribedEvents(), subscription.state(),
                subscription.createdAt(), subscription.createdBy());
    }

    public record SubscriptionView(
            Long id,
            String name,
            String targetUrl,
            List<String> subscribedEvents,
            String state,
            Instant createdAt,
            Long createdBy
    ) {}

    public record CreatedSubscription(
            Long id,
            String name,
            String targetUrl,
            String secretToken,
            List<String> subscribedEvents,
            String state,
            Instant createdAt,
            Long createdBy
    ) {}
}
