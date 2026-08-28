package com.greenwhite.dwh.instance.kwh.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenwhite.dwh.instance.kwh.repository.KwhOutboxRepository;
import com.greenwhite.dwh.instance.kwh.service.KwhWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

@Component
public class KwhOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(KwhOutboxWorker.class);

    private final KwhOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public KwhOutboxWorker(KwhOutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
    }

    @Scheduled(fixedDelay = 3000)
    public void processWebhooks() {
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
                String payloadJson = objectMapper.writeValueAsString(item.payload());
                String signature = KwhWebhookService.computeHmacSha256(payloadJson, item.secretToken());
                long timestamp = Instant.now().getEpochSecond();

                var response = restClient.post()
                        .uri(item.targetUrl())
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
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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
                log.warn("Webhook dispatch failed id={}, url={}, attempt {}/{}: {}",
                        item.id(), item.targetUrl(), newAttempts, item.maxAttempts(), lastError);
            }
        }
    }
}
