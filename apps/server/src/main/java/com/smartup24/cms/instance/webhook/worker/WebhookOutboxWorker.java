package com.smartup24.cms.instance.webhook.worker;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.service.WebhookProperties;
import com.smartup24.cms.instance.webhook.service.WebhookService;
import com.smartup24.cms.instance.webhook.service.WebhookTargetPolicy;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Component
public class WebhookOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboxWorker.class);

    private final WebhookOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final WebhookProperties properties;
    private final WebhookTargetPolicy targetPolicy;

    public WebhookOutboxWorker(
            WebhookOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            WebhookProperties properties,
            WebhookTargetPolicy targetPolicy) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.targetPolicy = targetPolicy;
        properties.validate();
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** One signed delivery to the subscription's checked address. */
    private org.springframework.http.ResponseEntity<Void> post(WebhookOutboxRepository.OutboxRecord item) {
        var target = targetPolicy.validate(item.targetUrl());
        long timestamp = Instant.now().getEpochSecond();
        String payloadJson = objectMapper.writeValueAsString(item.payload());
        String signature = WebhookService.computeHmacSha256(timestamp, payloadJson, item.secretToken());
        return restClient
                .post()
                .uri(target)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature-SHA256", signature)
                .header("X-Signature-Timestamp", String.valueOf(timestamp))
                .header("X-Event-Type", item.eventType())
                .header("X-Delivery-Id", String.valueOf(item.id()))
                .body(payloadJson)
                .retrieve()
                .toBodilessEntity();
    }

    @Scheduled(fixedDelay = 3000)
    public void processWebhooks() {
        if (!properties.isEnabled()) {
            return;
        }
        List<WebhookOutboxRepository.OutboxRecord> items = outboxRepository.fetchPending(20);
        if (items.isEmpty()) {
            return;
        }

        for (var item : items) {
            long startTime = System.currentTimeMillis();
            int httpStatus = 0;
            boolean isSuccess = false;
            String lastError = null;

            try {
                var response = post(item);

                httpStatus = response.getStatusCode().value();
                isSuccess = response.getStatusCode().is2xxSuccessful();

                if (isSuccess) {
                    outboxRepository.markSuccess(item.id(), item.claimToken(), httpStatus);
                } else {
                    lastError = "Non-2xx response: " + httpStatus;
                }
            } catch (ApiException exception) {
                log.info("webhook_target_rejected outboxId={} code={}", item.id(), exception.getErrorCode());
                lastError = "webhook_target_rejected";
            } catch (Exception exception) {
                log.warn("webhook_delivery_failed outboxId={} error={}", item.id(), exception.toString());
                lastError = "webhook_delivery_failed";
            }

            int durationMs = (int) (System.currentTimeMillis() - startTime);

            // Record audit delivery log
            try {
                outboxRepository.recordLog(item.subscriptionId(), item.eventType(), httpStatus, durationMs, isSuccess);
            } catch (Exception logEx) {
                log.error("Failed to record webhook log: {}", logEx.getMessage());
            }

            if (!isSuccess) {
                int newAttempts = item.attempts() + 1;
                boolean isDeadLetter = newAttempts >= item.maxAttempts();
                long backoffSeconds = (long) Math.pow(2, newAttempts) * 15;
                Instant nextAttempt = Instant.now().plusSeconds(backoffSeconds);

                outboxRepository.markFailed(
                        item.id(), item.claimToken(), newAttempts, nextAttempt, httpStatus, lastError, isDeadLetter);
                log.warn(
                        "Webhook dispatch failed id={}, attempt {}/{}: {}",
                        item.id(),
                        newAttempts,
                        item.maxAttempts(),
                        lastError);
            }
        }
    }
}
