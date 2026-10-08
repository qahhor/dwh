package com.smartup24.cms.instance.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.metrics.Backlog;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.service.WebhookProperties;
import com.smartup24.cms.instance.webhook.service.WebhookTargetPolicy;
import com.smartup24.cms.instance.webhook.worker.WebhookOutboxWorker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/** Plan 10/10, item 7.3: the webhook worker records failed deliveries, dead letters and its backlog. */
class WebhookOutboxWorkerMetricsTest {

    private final WebhookOutboxRepository repository = mock(WebhookOutboxRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("7.3: a rejected target is a failed attempt; the last attempt is a counted dead letter")
    void recordsFailuresAndDeadLetters() {
        when(repository.fetchPending(20)).thenReturn(List.of(item(1L, 0), item(2L, 4)));

        worker().processWebhooks();

        assertThat(registry.get("smc.outbox.delivery")
                        .tags("outbox", "webhook", "outcome", "retry")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("smc.outbox.delivery")
                        .tags("outbox", "webhook", "outcome", "dead_letter")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("smc.outbox.dead.letters")
                        .tag("outbox", "webhook")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("7.3: the backlog sample sets the webhook pending and lag gauges")
    void samplesTheBacklog() {
        when(repository.backlog()).thenReturn(new Backlog(3, 120));

        worker().sampleBacklog();

        assertThat(registry.get("smc.outbox.pending")
                        .tag("outbox", "webhook")
                        .gauge()
                        .value())
                .isEqualTo(3.0);
        assertThat(registry.get("smc.outbox.lag")
                        .tag("outbox", "webhook")
                        .gauge()
                        .value())
                .isEqualTo(120.0);
    }

    private WebhookOutboxWorker worker() {
        var properties = new WebhookProperties();
        properties.setEnabled(true);
        properties.setAllowedHosts(Set.of());
        properties.setAllowPrivateAddresses(false);
        return new WebhookOutboxWorker(
                repository,
                new ObjectMapper(),
                properties,
                new WebhookTargetPolicy(properties),
                ObservationRegistry.NOOP,
                meters(registry));
    }

    private static WebhookOutboxRepository.OutboxRecord item(long id, int attempts) {
        return new WebhookOutboxRepository.OutboxRecord(
                id,
                9L,
                "task.created",
                Map.of("id", 42),
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
                "http://127.0.0.1/internal",
                "signing-secret");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> meters(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}
