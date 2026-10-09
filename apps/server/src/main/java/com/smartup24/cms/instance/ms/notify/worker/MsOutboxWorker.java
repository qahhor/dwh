package com.smartup24.cms.instance.ms.notify.worker;

import com.smartup24.cms.instance.common.metrics.OutboxMetrics;
import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.ms.notify.repository.MsOutboxRepository;
import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.messenger.MessengerMessage;
import com.smartup24.cms.spi.sms.SmsMessage;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers the notification outbox through the active provider of each channel (ADR-0011).
 *
 * <p>The providers come from {@link ProviderRegistry}, not by type: with SMTP or a Telegram bot configured the
 * context holds the real provider next to the console stub, and a by-type injection stopped the start (plan 10/10,
 * item 0.8).
 */
@Component
public class MsOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(MsOutboxWorker.class);
    /** The outbox label of the delivery meters (plan 10/10, item 7.3). */
    static final String OUTBOX = "notification";
    /** The subject of a letter enqueued without one: the product name, the same in every language. */
    static final String DEFAULT_SUBJECT = "SmartupCMS";

    private final MsOutboxRepository outboxRepository;
    private final ProviderRegistry providers;
    private final OutboxMetrics metrics;

    /** A worker built by hand records no meters. */
    public MsOutboxWorker(MsOutboxRepository outboxRepository, ProviderRegistry providers) {
        this(outboxRepository, providers, OutboxMetrics.none(OUTBOX));
    }

    @Autowired
    public MsOutboxWorker(
            MsOutboxRepository outboxRepository, ProviderRegistry providers, ObjectProvider<MeterRegistry> meters) {
        this(outboxRepository, providers, OutboxMetrics.of(meters.getIfAvailable(), OUTBOX));
    }

    private MsOutboxWorker(MsOutboxRepository outboxRepository, ProviderRegistry providers, OutboxMetrics metrics) {
        this.outboxRepository = outboxRepository;
        this.providers = providers;
        this.metrics = metrics;
    }

    /** Plan 10/10, item 7.3: the backlog gauges, sampled apart from the delivery loop. */
    @Scheduled(fixedDelayString = "${smc.metrics.backlog-interval:PT30S}", initialDelayString = "PT10S")
    public void sampleBacklog() {
        try {
            metrics.backlog(outboxRepository.backlog());
        } catch (RuntimeException e) {
            log.warn("notification_outbox_backlog_sample_failed error={}", e.toString());
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void processOutbox() {
        List<MsOutboxRepository.OutboxRecord> items = outboxRepository.fetchPending(20);
        if (items.isEmpty()) {
            return;
        }

        for (var item : items) {
            long started = System.nanoTime();
            try {
                deliverItem(item);
                outboxRepository.markSuccess(item.id(), item.claimToken());
                metrics.delivered(System.nanoTime() - started);
                log.debug("Delivered notification outbox id={}", item.id());
            } catch (Exception e) {
                int newAttempts = item.attempts() + 1;
                boolean isDeadLetter = newAttempts >= item.maxAttempts();
                metrics.failed(System.nanoTime() - started, isDeadLetter);
                long backoffSeconds = (long) Math.pow(2, newAttempts) * 10;
                Instant nextAttempt = Instant.now().plusSeconds(backoffSeconds);

                outboxRepository.markFailed(
                        item.id(), item.claimToken(), newAttempts, nextAttempt, e.getMessage(), isDeadLetter);
                log.warn(
                        "Failed to deliver notification outbox id={}, attempt {}/{}: {}",
                        item.id(),
                        newAttempts,
                        item.maxAttempts(),
                        e.getMessage());
            }
        }
    }

    private void deliverItem(MsOutboxRepository.OutboxRecord item) {
        String body = item.payload() != null && item.payload().get("body") != null
                ? item.payload().get("body").toString()
                : "";
        String subject = item.payload() != null && item.payload().get("subject") != null
                ? item.payload().get("subject").toString()
                : DEFAULT_SUBJECT;
        String idempotencyKey = item.idempotencyKey().toString();

        switch (item.channel().toLowerCase()) {
            case "email" -> {
                var res = providers
                        .getActiveMailProvider()
                        .send(new MailMessage(item.recipient(), subject, body, null, List.of(), idempotencyKey));
                if (!res.isSuccess()) throw new RuntimeException("Email failed: " + res.errorMessage());
            }
            case "sms" -> {
                var res = providers
                        .getActiveSmsProvider()
                        .send(new SmsMessage(item.recipient(), body, null, idempotencyKey));
                if (!res.isSuccess()) throw new RuntimeException("SMS failed: " + res.errorMessage());
            }
            case "telegram" -> {
                var res = providers
                        .getActiveMessengerProvider()
                        .send(new MessengerMessage(item.recipient(), body, null, null, idempotencyKey));
                if (!res.isSuccess()) throw new RuntimeException("Telegram failed: " + res.errorMessage());
            }
            default -> throw new IllegalArgumentException("Unsupported notification channel: " + item.channel());
        }
    }
}
