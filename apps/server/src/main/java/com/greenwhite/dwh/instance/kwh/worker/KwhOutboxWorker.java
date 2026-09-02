package com.greenwhite.dwh.instance.kwh.worker;

import tools.jackson.databind.ObjectMapper;
import com.greenwhite.dwh.instance.kwh.repository.KwhOutboxRepository;
import com.greenwhite.dwh.instance.kwh.service.KwhWebhookProperties;
import com.greenwhite.dwh.instance.kwh.service.KwhWebhookService;
import com.greenwhite.dwh.instance.kwh.service.WebhookTargetPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

@Component
public class KwhOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(KwhOutboxWorker.class);

    private final KwhOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final KwhWebhookProperties properties;
    private final WebhookTargetPolicy targetPolicy;

    public KwhOutboxWorker(KwhOutboxRepository outboxRepository, ObjectMapper objectMapper,
                           KwhWebhookProperties properties, WebhookTargetPolicy targetPolicy) {
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

    @Scheduled(fixedDelay = 3000)
    public void processWebhooks() {
        if (!properties.isEnabled()) {
            return;
        }
        List<KwhOutboxRepository.KwhOutboxRecord> items = outboxRepository.fetchPending(20);
        if (items.isEmpty()) {
            return;
        }

        for (var item : items) {
            long startTime = System.currentTimeMillis();
            int httpStatus = 0;
            boolean isSuccess = false;
            String lastError = null;

            try {
                var target = targetPolicy.validate(item.targetUrl());
                String payloadJson = objectMapper.writeValueAsString(item.payload());
                String signature = KwhWebhookService.computeHmacSha256(payloadJson, item.secretToken());
                long timestamp = Instant.now().getEpochSecond();

                var response = restClient.post()
                        .uri(target)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature-SHA256", signature)
                        .header("X-Signature-Timestamp", String.valueOf(timestamp))
                        .header("X-Event-Type", item.eventType())
                        .body(payloadJson)
                        .retrieve()
                        .toBodilessEntity();

                httpStatus = response.getStatusCode().value();
                isSuccess = response.getStatusCode().is2xxSuccessful();

                if (isSuccess) {
                    outboxRepository.markSuccess(item.id(), httpStatus);
                } else {
                    lastError = "Non-2xx response: " + httpStatus;
                }
            } catch (com.greenwhite.dwh.instance.common.error.ApiException exception) {
                lastError = "webhook_target_rejected";
            } catch (Exception exception) {
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

                outboxRepository.markFailed(item.id(), newAttempts, nextAttempt, httpStatus, lastError, isDeadLetter);
                log.warn("Webhook dispatch failed id={}, attempt {}/{}: {}",
                        item.id(), newAttempts, item.maxAttempts(), lastError);
            }
        }
    }
}
