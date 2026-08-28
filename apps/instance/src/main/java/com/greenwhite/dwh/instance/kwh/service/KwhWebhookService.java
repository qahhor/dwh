package com.greenwhite.dwh.instance.kwh.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.kwh.repository.KwhOutboxRepository;
import com.greenwhite.dwh.instance.kwh.repository.KwhSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class KwhWebhookService {

    private final KwhSubscriptionRepository subscriptionRepository;
    private final KwhOutboxRepository outboxRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public KwhWebhookService(KwhSubscriptionRepository subscriptionRepository, KwhOutboxRepository outboxRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public List<KwhSubscriptionRepository.SubscriptionRecord> listSubscriptions() {
        return subscriptionRepository.listSubscriptions();
    }

    @Transactional
    public KwhSubscriptionRepository.SubscriptionRecord createSubscription(
            String name, String targetUrl, List<String> subscribedEvents, Long createdBy) {

        validateUrl(targetUrl);

        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        String secretToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        return subscriptionRepository.create(name, targetUrl, secretToken, subscribedEvents, createdBy);
    }

    @Transactional
    public void updateSubscription(Long id, String name, String targetUrl, List<String> subscribedEvents, String state) {
        if (targetUrl != null) {
            validateUrl(targetUrl);
        }
        subscriptionRepository.update(id, name, targetUrl, subscribedEvents, state);
    }

    @Transactional
    public void deleteSubscription(Long id) {
        subscriptionRepository.delete(id);
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

    private void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
                throw ApiException.badRequest(ErrorCode.INVALID_URL, "URL вебхука должен начинаться с http:// или https://");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest(ErrorCode.INVALID_URL, "Некорректный формат URL вебхука");
        }
    }
}
