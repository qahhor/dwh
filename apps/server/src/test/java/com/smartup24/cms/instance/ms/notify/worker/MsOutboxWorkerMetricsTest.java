package com.smartup24.cms.instance.ms.notify.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.metrics.Backlog;
import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.ms.notify.repository.MsOutboxRepository;
import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.mail.MailProvider;
import com.smartup24.cms.spi.mail.MailSendResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Plan 10/10, item 7.3: the notification worker records each delivery, each dead letter and its backlog. */
class MsOutboxWorkerMetricsTest {

    private final MsOutboxRepository outbox = mock(MsOutboxRepository.class);
    private final MailProvider mail = mock(MailProvider.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MsOutboxWorker worker = new MsOutboxWorker(outbox, providers(), meters(registry));

    @Test
    @DisplayName("7.3: a delivery, a retry and a dead letter are timed by outcome; the dead letter is counted")
    void recordsDeliveriesByOutcome() {
        when(mail.send(any(MailMessage.class)))
                .thenReturn(MailSendResult.success("m-1", 5))
                .thenReturn(MailSendResult.failure("SMTP_DOWN", "smtp down", 5))
                .thenReturn(MailSendResult.failure("SMTP_DOWN", "smtp down", 5));
        when(outbox.fetchPending(20)).thenReturn(List.of(item(1L, 0), item(2L, 0), item(3L, 2)));

        worker.processOutbox();

        assertThat(count("success")).isEqualTo(1);
        assertThat(count("retry")).isEqualTo(1);
        assertThat(count("dead_letter")).isEqualTo(1);
        assertThat(registry.get("smc.outbox.dead.letters")
                        .tag("outbox", "notification")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("7.3: the backlog sample sets the pending and lag gauges; a failed sample keeps the last one")
    void samplesTheBacklog() {
        when(outbox.backlog()).thenReturn(new Backlog(7, 42.5)).thenThrow(new IllegalStateException("db away"));

        worker.sampleBacklog();
        worker.sampleBacklog();

        assertThat(registry.get("smc.outbox.pending")
                        .tag("outbox", "notification")
                        .gauge()
                        .value())
                .isEqualTo(7.0);
        assertThat(registry.get("smc.outbox.lag")
                        .tag("outbox", "notification")
                        .gauge()
                        .value())
                .isEqualTo(42.5);
    }

    private long count(String outcome) {
        return registry.get("smc.outbox.delivery")
                .tags("outbox", "notification", "outcome", outcome)
                .timer()
                .count();
    }

    private static MsOutboxRepository.OutboxRecord item(long id, int attempts) {
        return new MsOutboxRepository.OutboxRecord(
                id,
                "email",
                "dev@example.com",
                "TASK_ASSIGNED",
                Map.of("subject", "s", "body", "b"),
                "PROCESSING",
                attempts,
                3,
                Instant.now(),
                UUID.randomUUID(),
                null,
                Instant.now(),
                null,
                UUID.randomUUID(),
                Instant.now());
    }

    private ProviderRegistry providers() {
        when(mail.getProviderCode()).thenReturn("smtp");
        return new ProviderRegistry(
                List.of(),
                List.of(mail),
                List.of(),
                List.of(),
                "local_disk",
                "smtp",
                "console_sms",
                "console_messenger");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> meters(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}
