package com.smartup24.cms.instance.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.service.WebhookProperties;
import com.smartup24.cms.instance.webhook.service.WebhookService;
import com.smartup24.cms.instance.webhook.service.WebhookTargetPolicy;
import com.smartup24.cms.instance.webhook.worker.WebhookOutboxWorker;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class WebhookOutboxWorkerSecurityTest {

    @Test
    void doesNotReadOrDispatchTheOutboxWhileWebhooksAreDisabled() {
        var repository = Mockito.mock(WebhookOutboxRepository.class);
        var properties = properties(false, Set.of(), false);
        var worker = new WebhookOutboxWorker(
                repository, new ObjectMapper(), properties, new WebhookTargetPolicy(properties));

        worker.processWebhooks();

        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void anEmptyOutboxEndsTheRoundWithoutFurtherWork() {
        var repository = Mockito.mock(WebhookOutboxRepository.class);
        var properties = properties(true, Set.of("127.0.0.1"), false);
        Mockito.when(repository.fetchPending(20)).thenReturn(java.util.List.of());
        var worker = new WebhookOutboxWorker(
                repository, new ObjectMapper(), properties, new WebhookTargetPolicy(properties));

        worker.processWebhooks();

        Mockito.verify(repository).fetchPending(20);
        Mockito.verifyNoMoreInteractions(repository);
    }

    @Test
    void revalidatesTheStoredTargetImmediatelyBeforeDispatch() {
        var repository = Mockito.mock(WebhookOutboxRepository.class);
        var properties = properties(true, Set.of("127.0.0.1"), false);
        UUID claimToken = UUID.randomUUID();
        var item = new WebhookOutboxRepository.OutboxRecord(
                5L,
                9L,
                "task.created",
                Map.of("id", 42),
                "PROCESSING",
                0,
                5,
                Instant.now(),
                null,
                null,
                Instant.now(),
                null,
                claimToken,
                Instant.now(),
                "http://127.0.0.1/internal",
                "signing-secret");
        Mockito.when(repository.fetchPending(20)).thenReturn(List.of(item));
        var worker = new WebhookOutboxWorker(
                repository, new ObjectMapper(), properties, new WebhookTargetPolicy(properties));

        worker.processWebhooks();

        Mockito.verify(repository)
                .markFailed(
                        Mockito.eq(5L),
                        Mockito.eq(claimToken),
                        Mockito.eq(1),
                        Mockito.any(Instant.class),
                        Mockito.eq(0),
                        Mockito.eq("webhook_target_rejected"),
                        Mockito.eq(false));
        Mockito.verify(repository)
                .recordLog(
                        Mockito.eq(9L), Mockito.eq("task.created"), Mockito.eq(0), Mockito.anyInt(), Mockito.eq(false));
    }

    @Test
    void boundsDeliveryTimeWhenTheRemoteEndpointStalls() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var repository = Mockito.mock(WebhookOutboxRepository.class);
            var properties = properties(true, Set.of("127.0.0.1"), true);
            properties.setConnectTimeout(Duration.ofMillis(100));
            properties.setReadTimeout(Duration.ofMillis(100));
            UUID claimToken = UUID.randomUUID();
            var item = new WebhookOutboxRepository.OutboxRecord(
                    11L,
                    12L,
                    "task.created",
                    Map.of("id", 42),
                    "PROCESSING",
                    0,
                    5,
                    Instant.now(),
                    null,
                    null,
                    Instant.now(),
                    null,
                    claimToken,
                    Instant.now(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/slow",
                    "signing-secret");
            Mockito.when(repository.fetchPending(20)).thenReturn(List.of(item));
            var worker = new WebhookOutboxWorker(
                    repository, new ObjectMapper(), properties, new WebhookTargetPolicy(properties));

            long startedAt = System.nanoTime();
            worker.processWebhooks();
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            Mockito.verify(repository)
                    .markFailed(
                            Mockito.eq(11L),
                            Mockito.eq(claimToken),
                            Mockito.eq(1),
                            Mockito.any(Instant.class),
                            Mockito.eq(0),
                            Mockito.eq("webhook_delivery_failed"),
                            Mockito.eq(false));
            assertThat(elapsedMillis).isLessThan(1_500);
        } finally {
            server.stop(0);
        }
    }

    /** ADR-0032, 6.9: the signature covers the timestamp it is sent with and the body, so a replay is detectable. */
    @Test
    void theDeliverySignsTheTimestampAndTheBody() throws Exception {
        var received = new AtomicReference<String[]>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.set(new String[] {
                    exchange.getRequestHeaders().getFirst(WebhookService.TIMESTAMP_HEADER),
                    exchange.getRequestHeaders().getFirst(WebhookService.SIGNATURE_HEADER),
                    body
                });
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try {
            var repository = Mockito.mock(WebhookOutboxRepository.class);
            var properties = properties(true, Set.of("127.0.0.1"), true);
            var item = new WebhookOutboxRepository.OutboxRecord(
                    21L,
                    22L,
                    "notes.updated",
                    Map.of("id", 42),
                    "PROCESSING",
                    0,
                    5,
                    Instant.now(),
                    null,
                    null,
                    Instant.now(),
                    null,
                    UUID.randomUUID(),
                    Instant.now(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                    "signing-secret");
            Mockito.when(repository.fetchPending(20)).thenReturn(List.of(item));

            new WebhookOutboxWorker(repository, new ObjectMapper(), properties, new WebhookTargetPolicy(properties))
                    .processWebhooks();

            String[] delivery = received.get();
            assertThat(delivery).as("the delivery arrived").isNotNull();
            long timestamp = Long.parseLong(delivery[0]);
            assertThat(timestamp).isCloseTo(Instant.now().getEpochSecond(), within(60L));
            assertThat(delivery[1])
                    .isEqualTo(WebhookService.computeHmacSha256(timestamp + "." + delivery[2], "signing-secret"))
                    .isNotEqualTo(WebhookService.computeHmacSha256(delivery[2], "signing-secret"));
        } finally {
            server.stop(0);
        }
    }

    private static WebhookProperties properties(
            boolean enabled, Set<String> allowedHosts, boolean allowPrivateAddresses) {
        var properties = new WebhookProperties();
        properties.setEnabled(enabled);
        properties.setAllowedHosts(allowedHosts);
        properties.setAllowPrivateAddresses(allowPrivateAddresses);
        return properties;
    }
}
