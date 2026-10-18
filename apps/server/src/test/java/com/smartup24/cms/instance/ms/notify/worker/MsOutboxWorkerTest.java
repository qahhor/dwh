package com.smartup24.cms.instance.ms.notify.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartup24.cms.instance.common.provider.ProviderRegistry;
import com.smartup24.cms.instance.ms.notify.repository.MsOutboxRepository;
import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.mail.MailProvider;
import com.smartup24.cms.spi.mail.MailSendResult;
import com.smartup24.cms.spi.messenger.MessengerMessage;
import com.smartup24.cms.spi.messenger.MessengerProvider;
import com.smartup24.cms.spi.messenger.MessengerSendResult;
import com.smartup24.cms.spi.sms.SmsMessage;
import com.smartup24.cms.spi.sms.SmsProvider;
import com.smartup24.cms.spi.sms.SmsSendResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MsOutboxWorkerTest {

    private final MsOutboxRepository outboxRepository = Mockito.mock(MsOutboxRepository.class);
    // With SMTP configured the console stub stays in the context: the worker must pick the active provider.
    private final MailProvider consoleMailProvider = mailProvider("console_mail");
    private final MailProvider mailProvider = mailProvider("smtp");
    private final SmsProvider smsProvider = Mockito.mock(SmsProvider.class);
    private final MessengerProvider messengerProvider = Mockito.mock(MessengerProvider.class);

    private final MsOutboxWorker worker = new MsOutboxWorker(outboxRepository, registry());

    private static MailProvider mailProvider(String code) {
        MailProvider provider = Mockito.mock(MailProvider.class);
        when(provider.getProviderCode()).thenReturn(code);
        return provider;
    }

    private ProviderRegistry registry() {
        when(smsProvider.getProviderCode()).thenReturn("console_sms");
        when(messengerProvider.getProviderCode()).thenReturn("console_messenger");
        return new ProviderRegistry(
                List.of(),
                List.of(consoleMailProvider, mailProvider),
                List.of(smsProvider),
                List.of(messengerProvider),
                "local_disk",
                "smtp",
                "console_sms",
                "console_messenger");
    }

    @Test
    @DisplayName("Успешная доставка Email отмечает запись как SUCCESS")
    void deliversEmailSuccessfully() {
        UUID claimToken = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        var item = new MsOutboxRepository.OutboxRecord(
                1L,
                "email",
                "dev@example.com",
                "TASK_ASSIGNED",
                Map.of("subject", "Новая задача", "body", "Вам назначена задача #42"),
                "PROCESSING",
                0,
                3,
                Instant.now(),
                idempotencyKey,
                null,
                Instant.now(),
                null,
                claimToken,
                Instant.now());

        when(outboxRepository.fetchPending(20)).thenReturn(List.of(item));
        when(mailProvider.send(any(MailMessage.class))).thenReturn(MailSendResult.success("msg-1", 50));

        worker.processOutbox();

        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailProvider).send(captor.capture());
        assertThat(captor.getValue().recipientEmail()).isEqualTo("dev@example.com");
        assertThat(captor.getValue().subject()).isEqualTo("Новая задача");
        assertThat(captor.getValue().textBody()).isEqualTo("Вам назначена задача #42");
        assertThat(captor.getValue().htmlBody()).isNull();
        assertThat(captor.getValue().attachments()).isEmpty();
        verify(consoleMailProvider, never()).send(any(MailMessage.class));

        verify(outboxRepository).markSuccess(1L, claimToken);
    }

    @Test
    @DisplayName("Успешная доставка Telegram отмечает запись как SUCCESS")
    void deliversTelegramSuccessfully() {
        UUID claimToken = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        var item = new MsOutboxRepository.OutboxRecord(
                2L,
                "telegram",
                "123456789",
                "TASK_ASSIGNED",
                Map.of("body", "Вам назначена задача #42"),
                "PROCESSING",
                0,
                3,
                Instant.now(),
                idempotencyKey,
                null,
                Instant.now(),
                null,
                claimToken,
                Instant.now());

        when(outboxRepository.fetchPending(20)).thenReturn(List.of(item));
        when(messengerProvider.send(any(MessengerMessage.class))).thenReturn(MessengerSendResult.success("msg-2", 30));

        worker.processOutbox();

        ArgumentCaptor<MessengerMessage> captor = ArgumentCaptor.forClass(MessengerMessage.class);
        verify(messengerProvider).send(captor.capture());
        assertThat(captor.getValue().recipientChatId()).isEqualTo("123456789");
        assertThat(captor.getValue().textMarkdown()).isEqualTo("Вам назначена задача #42");

        verify(outboxRepository).markSuccess(2L, claimToken);
    }

    @Test
    @DisplayName("ADR-0045: a letter carries its attachment, a Telegram message its link button")
    void deliversTheAttachmentAndTheLinkButton() {
        UUID claimToken = UUID.randomUUID();
        var letter = record(
                5L, "email", "reader@example.com", Map.of("subject", "Отчёт", "body", "Во вложении"), claimToken);
        var message = record(
                6L,
                "telegram",
                "42",
                Map.of("body", "Отчёт готов", "buttonText", "Открыть", "buttonUrl", "https://cms.example/e/x"),
                claimToken);
        byte[] file = {1, 2, 3};
        when(outboxRepository.fetchPending(20)).thenReturn(List.of(letter, message));
        when(outboxRepository.attachment(5L))
                .thenReturn(
                        java.util.Optional.of(new MsOutboxRepository.Attachment("report.xlsx", "application/x", file)));
        when(mailProvider.send(any(MailMessage.class))).thenReturn(MailSendResult.success("m", 1));
        when(messengerProvider.send(any(MessengerMessage.class))).thenReturn(MessengerSendResult.success("t", 1));

        worker.processOutbox();

        ArgumentCaptor<MailMessage> mail = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailProvider).send(mail.capture());
        assertThat(mail.getValue().attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.filename()).isEqualTo("report.xlsx");
            assertThat(attachment.content()).containsExactly(1, 2, 3);
        });
        ArgumentCaptor<MessengerMessage> telegram = ArgumentCaptor.forClass(MessengerMessage.class);
        verify(messengerProvider).send(telegram.capture());
        assertThat(telegram.getValue().inlineButtonText()).isEqualTo("Открыть");
        assertThat(telegram.getValue().inlineButtonUrl()).isEqualTo("https://cms.example/e/x");
        verify(outboxRepository).markSuccess(5L, claimToken);
        verify(outboxRepository).markSuccess(6L, claimToken);
    }

    private static MsOutboxRepository.OutboxRecord record(
            long id, String channel, String recipient, Map<String, Object> payload, UUID claimToken) {
        return new MsOutboxRepository.OutboxRecord(
                id,
                channel,
                recipient,
                "report_mailing",
                payload,
                "PROCESSING",
                0,
                5,
                Instant.now(),
                UUID.randomUUID(),
                null,
                Instant.now(),
                null,
                claimToken,
                Instant.now());
    }

    @Test
    @DisplayName("Ошибка провайдера вызывает markFailed с экспоненциальной задержкой")
    void handlesProviderFailureWithRetry() {
        UUID claimToken = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        var item = new MsOutboxRepository.OutboxRecord(
                3L,
                "email",
                "fail@example.com",
                "TASK_ASSIGNED",
                Map.of("subject", "Тест", "body", "Текст"),
                "PROCESSING",
                0,
                3,
                Instant.now(),
                idempotencyKey,
                null,
                Instant.now(),
                null,
                claimToken,
                Instant.now());

        when(outboxRepository.fetchPending(20)).thenReturn(List.of(item));
        when(mailProvider.send(any(MailMessage.class)))
                .thenReturn(MailSendResult.failure("SMTP_ERR", "SMTP connection timeout", 100))
                .thenReturn(MailSendResult.success("msg-retry", 50));

        worker.processOutbox();
        worker.processOutbox();

        ArgumentCaptor<MailMessage> messages = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailProvider, times(2)).send(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(MailMessage::idempotencyKey)
                .containsExactly(idempotencyKey.toString(), idempotencyKey.toString());
        verify(outboxRepository).markSuccess(3L, claimToken);

        verify(outboxRepository)
                .markFailed(
                        eq(3L),
                        eq(claimToken),
                        eq(1),
                        any(Instant.class),
                        eq("Email failed: SMTP connection timeout"),
                        eq(false));
    }

    @Test
    @DisplayName("Превышение максимального числа попыток помечает запись как dead-letter")
    void marksDeadLetterWhenMaxAttemptsReached() {
        UUID claimToken = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        var item = new MsOutboxRepository.OutboxRecord(
                4L,
                "sms",
                "+998901234567",
                "TASK_DEADLINE",
                Map.of("body", "Дедлайн"),
                "PROCESSING",
                2,
                3,
                Instant.now(),
                idempotencyKey,
                null,
                Instant.now(),
                null,
                claimToken,
                Instant.now());

        when(outboxRepository.fetchPending(20)).thenReturn(List.of(item));
        when(smsProvider.send(any(SmsMessage.class))).thenReturn(SmsSendResult.failure("GW_ERR", "Gateway error", 50));

        worker.processOutbox();

        verify(outboxRepository)
                .markFailed(
                        eq(4L),
                        eq(claimToken),
                        eq(3),
                        any(Instant.class),
                        eq("SMS failed: Gateway error"),
                        eq(true) // isDeadLetter = true
                        );
    }
}
