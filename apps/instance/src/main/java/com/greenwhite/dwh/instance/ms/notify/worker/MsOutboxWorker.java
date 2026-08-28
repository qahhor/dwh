package com.greenwhite.dwh.instance.ms.notify.worker;

import com.greenwhite.dwh.instance.ms.notify.repository.MsOutboxRepository;
import com.greenwhite.dwh.spi.mail.MailMessage;
import com.greenwhite.dwh.spi.mail.MailProvider;
import com.greenwhite.dwh.spi.messenger.MessengerMessage;
import com.greenwhite.dwh.spi.messenger.MessengerProvider;
import com.greenwhite.dwh.spi.sms.SmsMessage;
import com.greenwhite.dwh.spi.sms.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class MsOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(MsOutboxWorker.class);

    private final MsOutboxRepository outboxRepository;
    private final MailProvider mailProvider;
    private final SmsProvider smsProvider;
    private final MessengerProvider messengerProvider;

    public MsOutboxWorker(
            MsOutboxRepository outboxRepository,
            MailProvider mailProvider,
            SmsProvider smsProvider,
            MessengerProvider messengerProvider) {
        this.outboxRepository = outboxRepository;
        this.mailProvider = mailProvider;
        this.smsProvider = smsProvider;
        this.messengerProvider = messengerProvider;
    }

    @Scheduled(fixedDelay = 2000)
    public void processOutbox() {
        List<MsOutboxRepository.OutboxRecord> items = outboxRepository.fetchPending(20);
        if (items.isEmpty()) {
            return;
        }

        for (var item : items) {
            try {
                deliverItem(item);
                outboxRepository.markSuccess(item.id());
                log.debug("Delivered notification outbox id={}", item.id());
            } catch (Exception e) {
                int newAttempts = item.attempts() + 1;
                boolean isDeadLetter = newAttempts >= item.maxAttempts();
                long backoffSeconds = (long) Math.pow(2, newAttempts) * 10;
                Instant nextAttempt = Instant.now().plusSeconds(backoffSeconds);

                outboxRepository.markFailed(item.id(), newAttempts, nextAttempt, e.getMessage(), isDeadLetter);
                log.warn("Failed to deliver notification outbox id={}, attempt {}/{}: {}",
                        item.id(), newAttempts, item.maxAttempts(), e.getMessage());
            }
        }
    }

    private void deliverItem(MsOutboxRepository.OutboxRecord item) {
        String body = item.payload() != null && item.payload().get("body") != null
                ? item.payload().get("body").toString() : "";
        String subject = item.payload() != null && item.payload().get("subject") != null
                ? item.payload().get("subject").toString() : "РЈРІРµРґРѕРјР»РµРЅРёРµ DWH";
        String idempotencyKey = item.idempotencyKey().toString();

        switch (item.channel().toLowerCase()) {
            case "email" -> {
                var res = mailProvider.send(new MailMessage(
                        item.recipient(), subject, body, null, List.of(), idempotencyKey
                ));
                if (!res.isSuccess()) throw new RuntimeException("Email failed: " + res.errorMessage());
            }
            case "sms" -> {
                var res = smsProvider.send(new SmsMessage(
                        item.recipient(), body, "DWH", idempotencyKey
                ));
                if (!res.isSuccess()) throw new RuntimeException("SMS failed: " + res.errorMessage());
            }
            case "telegram" -> {
                var res = messengerProvider.send(new MessengerMessage(
                        item.recipient(), body, null, null, idempotencyKey
                ));
                if (!res.isSuccess()) throw new RuntimeException("Telegram failed: " + res.errorMessage());
            }
            default -> throw new IllegalArgumentException("Unsupported notification channel: " + item.channel());
        }
    }
}
