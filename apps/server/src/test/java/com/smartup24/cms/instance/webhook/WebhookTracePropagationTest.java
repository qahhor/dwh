package com.smartup24.cms.instance.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.service.WebhookProperties;
import com.smartup24.cms.instance.webhook.service.WebhookTargetPolicy;
import com.smartup24.cms.instance.webhook.worker.WebhookOutboxWorker;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/** Plan 10/10, item 7.2: a webhook delivery carries the W3C traceparent of the job's trace to the receiver. */
class WebhookTracePropagationTest {

    @Test
    @DisplayName("7.2: доставка вебхука передаёт получателю traceparent трассы задания")
    void aDeliveryPropagatesTheTraceOfItsJob() throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            try {
                received.set(exchange.getRequestHeaders().getFirst("traceparent"));
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build()) {
            var otel = provider.get("test");
            var tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> {});
            var propagator =
                    new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig()
                    .observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                            new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                            new DefaultTracingObservationHandler(tracer)));
            var repository = Mockito.mock(WebhookOutboxRepository.class);
            UUID claimToken = UUID.randomUUID();
            Mockito.when(repository.fetchPending(20))
                    .thenReturn(List.of(item(claimToken, server.getAddress().getPort())));
            var properties = new WebhookProperties();
            properties.setEnabled(true);
            properties.setAllowedHosts(Set.of("127.0.0.1"));
            properties.setAllowPrivateAddresses(true);
            var worker = new WebhookOutboxWorker(
                    repository, new ObjectMapper(), properties, new WebhookTargetPolicy(properties), registry);

            var jobTraceId = new AtomicReference<String>();
            Observation.createNotStarted("webhook-job", registry).observe(() -> {
                jobTraceId.set(tracer.currentSpan().context().traceId());
                worker.processWebhooks();
            });

            Mockito.verify(repository).markSuccess(31L, claimToken, 200);
            assertThat(received.get()).matches("00-" + jobTraceId.get() + "-[0-9a-f]{16}-[0-9a-f]{2}");
        } finally {
            server.stop(0);
        }
    }

    private static WebhookOutboxRepository.OutboxRecord item(UUID claimToken, int port) {
        return new WebhookOutboxRepository.OutboxRecord(
                31L,
                32L,
                "notes.created",
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
                "http://127.0.0.1:" + port + "/webhook",
                "signing-secret");
    }
}
