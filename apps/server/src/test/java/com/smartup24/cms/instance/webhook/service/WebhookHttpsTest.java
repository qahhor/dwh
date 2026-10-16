package com.smartup24.cms.instance.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.worker.WebhookOutboxWorker;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** NFR-SEC-06: private-network opt-in never permits unencrypted delivery. */
class WebhookHttpsTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsHttpBeforeDnsEvenWhenPrivateAddressesAreAllowed(boolean allowPrivate) {
        var properties = properties(allowPrivate);
        var policy = new WebhookTargetPolicy(properties, host -> {
            throw new AssertionError("An unencrypted target must be rejected before DNS");
        });
        for (String scheme : List.of("http", "HtTp")) {
            assertThatThrownBy(() -> policy.validate(scheme + "://127.0.0.1/events"))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("messageKey", "error.webhook.url_scheme");
        }
    }

    @Test
    void storedHttpIsNeverSentEvenToAnAllowListedPrivateReceiver() throws Exception {
        var received = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events", exchange -> {
            received.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var repository = mock(WebhookOutboxRepository.class);
            var properties = properties(true);
            UUID claim = UUID.randomUUID();
            var item = new WebhookOutboxRepository.OutboxRecord(
                    1L,
                    2L,
                    "x.created",
                    Map.of("id", 3),
                    "PROCESSING",
                    0,
                    5,
                    Instant.now(),
                    null,
                    null,
                    Instant.now(),
                    null,
                    claim,
                    Instant.now(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/events",
                    "disposable-signing-value");
            when(repository.fetchPending(20)).thenReturn(List.of(item));
            new WebhookOutboxWorker(
                            repository,
                            new ObjectMapper(),
                            properties,
                            new WebhookTargetPolicy(properties),
                            ObservationRegistry.NOOP)
                    .processWebhooks();

            assertThat(received).hasValue(0);
            verify(repository)
                    .markFailed(eq(1L), eq(claim), eq(1), any(), eq(0), eq("webhook_target_rejected"), eq(false));
        } finally {
            server.stop(0);
        }
    }

    private static WebhookProperties properties(boolean allowPrivate) {
        var properties = new WebhookProperties();
        properties.setEnabled(true);
        properties.setAllowedHosts(Set.of("127.0.0.1"));
        properties.setAllowPrivateAddresses(allowPrivate);
        return properties;
    }
}
