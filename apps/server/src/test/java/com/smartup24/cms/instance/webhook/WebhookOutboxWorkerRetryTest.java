package com.smartup24.cms.instance.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.metrics.Backlog;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository.OutboxRecord;
import com.smartup24.cms.instance.webhook.service.WebhookProperties;
import com.smartup24.cms.instance.webhook.service.WebhookTargetPolicy;
import com.smartup24.cms.instance.webhook.worker.WebhookOutboxWorker;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * The delivery round against a live receiver: a 2xx marks the item sent, a refusal waits for a doubling pause, the
 * last attempt makes a dead letter, and a failure to write the delivery log stops neither the round nor the retry.
 */
class WebhookOutboxWorkerRetryTest {

    private HttpServer server;
    private final WebhookOutboxRepository repository = mock(WebhookOutboxRepository.class);
    private WebhookOutboxWorker worker;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/refuse", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        var properties = new WebhookProperties();
        properties.setEnabled(true);
        properties.setAllowedHosts(Set.of("127.0.0.1"));
        properties.setAllowPrivateAddresses(true);
        worker = new WebhookOutboxWorker(
                repository,
                new ObjectMapper(),
                properties,
                new WebhookTargetPolicy(properties),
                ObservationRegistry.NOOP);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("A 2xx answer marks the item sent with its status and logs a success")
    void successIsMarkedSent() {
        var item = item(1L, "/ok", 0);
        when(repository.fetchPending(20)).thenReturn(List.of(item));

        worker.processWebhooks();

        verify(repository).markSuccess(1L, item.claimToken(), 204);
        verify(repository).recordLog(eq(10L), eq("x.created"), eq(204), anyInt(), eq(true));
        verify(repository, never()).markFailed(anyLong(), any(), anyInt(), any(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("A refused delivery waits for a doubling pause; the last attempt makes a dead letter")
    void refusalIsRetriedThenDeadLettered() {
        var first = item(2L, "/refuse", 0);
        var last = item(3L, "/refuse", 4);
        when(repository.fetchPending(20)).thenReturn(List.of(first, last));
        Instant before = Instant.now();

        worker.processWebhooks();

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(repository)
                .markFailed(
                        eq(2L),
                        eq(first.claimToken()),
                        eq(1),
                        next.capture(),
                        eq(0),
                        eq("webhook_delivery_failed"),
                        eq(false));
        assertThat(next.getValue())
                .isBetween(before.plusSeconds(29), Instant.now().plusSeconds(31));
        verify(repository)
                .markFailed(
                        eq(3L),
                        eq(last.claimToken()),
                        eq(5),
                        any(Instant.class),
                        eq(0),
                        eq("webhook_delivery_failed"),
                        eq(true));
        verify(repository, never()).markSuccess(anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("A failing delivery log neither stops the round nor loses the outcome")
    void failingDeliveryLogIsSurvived() {
        var sent = item(4L, "/ok", 0);
        var refused = item(5L, "/refuse", 0);
        when(repository.fetchPending(20)).thenReturn(List.of(sent, refused));
        doThrow(new IllegalStateException("log table locked"))
                .when(repository)
                .recordLog(anyLong(), anyString(), anyInt(), anyInt(), anyBoolean());

        assertThatCode(() -> worker.processWebhooks()).doesNotThrowAnyException();

        verify(repository).markSuccess(4L, sent.claimToken(), 204);
        verify(repository).markFailed(eq(5L), eq(refused.claimToken()), eq(1), any(), eq(0), anyString(), eq(false));
    }

    @Test
    @DisplayName("The backlog sample reads the outbox and survives a failing read")
    void backlogSample() {
        when(repository.backlog()).thenReturn(new Backlog(3, 12.5)).thenThrow(new IllegalStateException("down"));

        worker.sampleBacklog();
        assertThatCode(() -> worker.sampleBacklog()).doesNotThrowAnyException();

        verify(repository, org.mockito.Mockito.times(2)).backlog();
    }

    private OutboxRecord item(long id, String path, int attempts) {
        return new OutboxRecord(
                id,
                10L,
                "x.created",
                Map.of("id", id),
                "PROCESSING",
                attempts,
                5,
                Instant.now(),
                null,
                null,
                Instant.now(),
                null,
                UUID.randomUUID(),
                Instant.now(),
                "http://127.0.0.1:" + server.getAddress().getPort() + path,
                "signing-secret");
    }
}
