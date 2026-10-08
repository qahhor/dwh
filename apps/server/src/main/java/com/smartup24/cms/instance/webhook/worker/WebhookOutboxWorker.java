package com.smartup24.cms.instance.webhook.worker;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.metrics.OutboxMetrics;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.service.WebhookProperties;
import com.smartup24.cms.instance.webhook.service.WebhookService;
import com.smartup24.cms.instance.webhook.service.WebhookTargetPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Component
public class WebhookOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboxWorker.class);
    /** The outbox label of the delivery meters (plan 10/10, item 7.3). */
    static final String OUTBOX = "webhook";

    private final WebhookOutboxRepository outboxRepository;
    private final JsonColumns json;
    private final RestClient restClient;
    private final WebhookProperties properties;
    private final WebhookTargetPolicy targetPolicy;
    private final OutboxMetrics metrics;

    /** A worker built by hand records no meters. */
    public WebhookOutboxWorker(
            WebhookOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            WebhookProperties properties,
            WebhookTargetPolicy targetPolicy,
            ObservationRegistry observationRegistry) {
        this(
                outboxRepository,
                objectMapper,
                properties,
                targetPolicy,
                observationRegistry,
                OutboxMetrics.none(OUTBOX));
    }

    @Autowired
    public WebhookOutboxWorker(
            WebhookOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            WebhookProperties properties,
            WebhookTargetPolicy targetPolicy,
            ObservationRegistry observationRegistry,
            ObjectProvider<MeterRegistry> meters) {
        this(
                outboxRepository,
                objectMapper,
                properties,
                targetPolicy,
                observationRegistry,
                OutboxMetrics.of(meters.getIfAvailable(), OUTBOX));
    }

    private WebhookOutboxWorker(
            WebhookOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            WebhookProperties properties,
            WebhookTargetPolicy targetPolicy,
            ObservationRegistry observationRegistry,
            OutboxMetrics metrics) {
        this.metrics = metrics;
        this.outboxRepository = outboxRepository;
        this.json = new JsonColumns(objectMapper, "kwh_outbox");
        this.properties = properties;
        this.targetPolicy = targetPolicy;
        properties.validate();
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        // The observation registry makes each delivery a client span and sends the W3C traceparent of the job's trace
        // to the receiver (plan 10/10, item 7.2).
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }

    /** One signed delivery to the subscription's checked address. */
    private org.springframework.http.ResponseEntity<Void> post(WebhookOutboxRepository.OutboxRecord item) {
        var target = targetPolicy.validate(item.targetUrl());
        long timestamp = Instant.now().getEpochSecond();
        String payloadJson = json.write(item.payload());
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
            long started = System.nanoTime();
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

            if (isSuccess) {
                metrics.delivered(System.nanoTime() - started);
            } else {
                retryOrDeadLetter(item, started, httpStatus, lastError);
            }
        }
    }

    /** A failed attempt: the item waits for its next attempt or, out of attempts, becomes a dead letter. */
    private void retryOrDeadLetter(
            WebhookOutboxRepository.OutboxRecord item, long started, int httpStatus, String lastError) {
        int newAttempts = item.attempts() + 1;
        boolean isDeadLetter = newAttempts >= item.maxAttempts();
        metrics.failed(System.nanoTime() - started, isDeadLetter);
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

    /** Plan 10/10, item 7.3: the backlog gauges, sampled apart from the delivery loop. */
    @Scheduled(fixedDelayString = "${smc.metrics.backlog-interval:PT30S}", initialDelayString = "PT10S")
    public void sampleBacklog() {
        try {
            metrics.backlog(outboxRepository.backlog());
        } catch (RuntimeException e) {
            log.warn("webhook_outbox_backlog_sample_failed error={}", e.toString());
        }
    }
}
