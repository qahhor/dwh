package com.greenwhite.dwh.instance.ms.notify;

import com.greenwhite.dwh.instance.common.provider.NotificationChannelStartupCheck;
import com.greenwhite.dwh.instance.common.provider.ProviderRegistry;
import com.greenwhite.dwh.instance.ms.notify.provider.ConsoleMailProvider;
import com.greenwhite.dwh.instance.ms.notify.provider.ConsoleMessengerProvider;
import com.greenwhite.dwh.instance.ms.notify.provider.ConsoleSmsProvider;
import com.greenwhite.dwh.instance.ms.notify.provider.SmtpMailProvider;
import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.mail.MailMessage;
import com.greenwhite.dwh.spi.mail.MailProvider;
import com.greenwhite.dwh.spi.mail.MailSendResult;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Каналы доставки (FR-NOTIF-3/4/5).
 *
 * Ревизия AUDIT-05 показала: все три канала были заглушками, при этом заглушка
 * рапортовала об успешной отправке и о собственном здоровье. Восстановление
 * пароля и OTP не доходили ни до кого, а система выглядела исправной.
 * Эти тесты закрепляют оба свойства: заглушка честно говорит, что она заглушка,
 * а настоящий SMTP-провайдер не бросает исключение наружу.
 */
class NotificationChannelTest {

    private static final MailMessage LETTER = new MailMessage(
            "user@example.com", "Восстановление пароля", "<p>код</p>", "код", List.of(), "idem-1");

    @Test
    @DisplayName("Заглушки каналов объявляют себя нездоровыми, а не исправными")
    void stubProvidersReportUnhealthy() {
        assertThat(new ConsoleMailProvider().checkHealth().isHealthy()).isFalse();
        assertThat(new ConsoleSmsProvider().checkHealth().isHealthy()).isFalse();
        assertThat(new ConsoleMessengerProvider().checkHealth().isHealthy()).isFalse();
    }

    @Test
    @DisplayName("При старте на заглушках перечисляются все ненастроенные каналы")
    void startupCheckListsStubChannels() {
        var check = new NotificationChannelStartupCheck(registryWith(new ConsoleMailProvider()));

        assertThat(check.findStubChannels())
                .hasSize(3)
                .anySatisfy(s -> assertThat(s).contains("почта"))
                .anySatisfy(s -> assertThat(s).contains("SMS"))
                .anySatisfy(s -> assertThat(s).contains("мессенджер"));
    }

    @Test
    @DisplayName("Настроенный канал почты исчезает из списка ненастроенных")
    void startupCheckIgnoresConfiguredChannel() {
        var check = new NotificationChannelStartupCheck(registryWith(new StubRealMailProvider()));

        assertThat(check.findStubChannels())
                .noneSatisfy(s -> assertThat(s).contains("почта"));
    }

    @Test
    @DisplayName("SMTP: успешная отправка отдаёт success и действительно вызывает шлюз")
    void smtpSendsThroughGateway() {
        JavaMailSender sender = Mockito.mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(emptyMime());

        var result = new SmtpMailProvider(sender, "no-reply@dwh.local", "DWH").send(LETTER);

        assertThat(result.isSuccess()).isTrue();
        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("SMTP: отказ шлюза возвращается как failure, а не бросается наружу")
    void smtpFailureIsReportedNotThrown() {
        JavaMailSender sender = Mockito.mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(emptyMime());
        doThrow(new MailSendException("шлюз недоступен")).when(sender).send(any(MimeMessage.class));

        var result = new SmtpMailProvider(sender, "no-reply@dwh.local", "DWH").send(LETTER);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("smtp_send_failed");
    }

    private static MimeMessage emptyMime() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private static ProviderRegistry registryWith(MailProvider mailProvider) {
        StorageProvider storage = Mockito.mock(StorageProvider.class);
        when(storage.getProviderCode()).thenReturn("local");

        return new ProviderRegistry(
                List.of(storage),
                List.of(mailProvider),
                List.of(new ConsoleSmsProvider()),
                List.of(new ConsoleMessengerProvider()),
                "local", mailProvider.getProviderCode(), "console_sms", "console_messenger");
    }

    /** Провайдер с «настоящим» кодом: проверяем, что признак заглушки — код, а не класс. */
    private static class StubRealMailProvider implements MailProvider {
        @Override
        public String getProviderCode() {
            return "smtp";
        }

        @Override
        public MailSendResult send(MailMessage message) {
            return MailSendResult.success("id", 1);
        }

        @Override
        public ProviderHealth checkHealth() {
            return ProviderHealth.healthy(getProviderCode(), 1);
        }
    }
}
