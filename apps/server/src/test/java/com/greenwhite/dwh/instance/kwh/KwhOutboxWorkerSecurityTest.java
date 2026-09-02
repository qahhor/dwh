package com.greenwhite.dwh.instance.kwh;

import com.sun.net.httpserver.HttpServer;
import com.greenwhite.dwh.instance.kwh.repository.KwhOutboxRepository;
import com.greenwhite.dwh.instance.kwh.service.KwhWebhookProperties;
import com.greenwhite.dwh.instance.kwh.service.WebhookTargetPolicy;
import com.greenwhite.dwh.instance.kwh.worker.KwhOutboxWorker;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KwhOutboxWorkerSecurityTest {

    @Test
    void doesNotReadOrDispatchTheOutboxWhileWebhooksAreDisabled() {
        var repository = Mockito.mock(KwhOutboxRepository.class);
        var properties = properties(false, Set.of(), false);
        var worker = new KwhOutboxWorker(repository, new ObjectMapper(), properties,
                new WebhookTargetPolicy(properties));

        worker.processWebhooks();

        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void revalidatesTheStoredTargetImmediatelyBeforeDispatch() {
        var repository = Mockito.mock(KwhOutboxRepository.class);
        var properties = properties(true, Set.of("127.0.0.1"), false);
        UUID claimToken = UUID.randomUUID();
        var item = new KwhOutboxRepository.KwhOutboxRecord(
                5L, 9L, "task.created", Map.of("id", 42), "PROCESSING",
                0, 5, Instant.now(), null, null, Instant.now(), null,
                claimToken, Instant.now(),
                "http://127.0.0.1/internal", "signing-secret");
        Mockito.when(repository.fetchPending(20)).thenReturn(List.of(item));
        var worker = new KwhOutboxWorker(repository, new ObjectMapper(), properties,
                new WebhookTargetPolicy(properties));

        worker.processWebhooks();

        Mockito.verify(repository).markFailed(
                Mockito.eq(5L), Mockito.eq(claimToken), Mockito.eq(1),
                Mockito.any(Instant.class), Mockito.eq(0),
                Mockito.eq("webhook_target_rejected"), Mockito.eq(false));
        Mockito.verify(repository).recordLog(
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
            var repository = Mockito.mock(KwhOutboxRepository.class);
            var properties = properties(true, Set.of("127.0.0.1"), true);
            properties.setConnectTimeout(Duration.ofMillis(100));
            properties.setReadTimeout(Duration.ofMillis(100));
            UUID claimToken = UUID.randomUUID();
            var item = new KwhOutboxRepository.KwhOutboxRecord(
                    11L, 12L, "task.created", Map.of("id", 42), "PROCESSING",
                    0, 5, Instant.now(), null, null, Instant.now(), null,
                    claimToken, Instant.now(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/slow", "signing-secret");
            Mockito.when(repository.fetchPending(20)).thenReturn(List.of(item));
            var worker = new KwhOutboxWorker(repository, new ObjectMapper(), properties,
                    new WebhookTargetPolicy(properties));

            long startedAt = System.nanoTime();
            worker.processWebhooks();
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            Mockito.verify(repository).markFailed(
                    Mockito.eq(11L), Mockito.eq(claimToken), Mockito.eq(1),
                    Mockito.any(Instant.class), Mockito.eq(0),
                    Mockito.eq("webhook_delivery_failed"), Mockito.eq(false));
            assertThat(elapsedMillis).isLessThan(1_500);
        } finally {
            server.stop(0);
        }
    }

    private static KwhWebhookProperties properties(boolean enabled, Set<String> allowedHosts,
                                                    boolean allowPrivateAddresses) {
        var properties = new KwhWebhookProperties();
        properties.setEnabled(enabled);
        properties.setAllowedHosts(allowedHosts);
        properties.setAllowPrivateAddresses(allowPrivateAddresses);
        return properties;
    }
}
