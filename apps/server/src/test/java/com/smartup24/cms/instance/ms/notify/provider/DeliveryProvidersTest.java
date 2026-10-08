package com.smartup24.cms.instance.ms.notify.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.messenger.MessengerMessage;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The real delivery providers (FR-NOTIF-3, FR-NOTIF-4): what they send, how an answer of the remote side becomes a
 * result, and that a failure is a result for the outbox to retry, never an exception or a leaked token.
 */
class DeliveryProvidersTest {

    private static final String TOKEN = "123456:test-bot-token";

    private HttpServer telegram;
    private final Deque<String[]> answers = new ArrayDeque<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startTelegram() throws Exception {
        telegram = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        telegram.createContext("/bot" + TOKEN, exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String[] answer = answers.isEmpty() ? new String[] {"500", "{}"} : answers.poll();
            byte[] body = answer[1].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(Integer.parseInt(answer[0]), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        telegram.start();
    }

    @AfterEach
    void stopTelegram() {
        telegram.stop(0);
    }

    @Test
    @DisplayName("Telegram: an accepted message returns its id, with the inline button in the request")
    void telegramAcceptedMessage() {
        answers.add(new String[] {"200", "{\"ok\":true,\"result\":{\"message_id\":42}}"});
        answers.add(new String[] {"200", "{\"ok\":true,\"result\":{}}"});
        var provider = telegram();

        var sent =
                provider.send(new MessengerMessage("777", "*Task* assigned", "Open", "https://cms.example/t/1", "k1"));
        assertThat(lastBody.get()).contains("inline_keyboard").contains("https://cms.example/t/1");
        var noId = provider.send(new MessengerMessage("777", "text", null, null, "k2"));

        assertThat(sent.isSuccess()).isTrue();
        assertThat(sent.externalMessageId()).isEqualTo("42");
        assertThat(noId.externalMessageId()).isEqualTo("k2");
        assertThat(lastBody.get()).contains("\"chat_id\":\"777\"").doesNotContain("inline_keyboard");
        assertThat(provider.getProviderCode()).isEqualTo("telegram");
    }

    @Test
    @DisplayName("Telegram: a refused message and a failed call are failures that never show the token")
    void telegramFailures() {
        answers.add(new String[] {"200", "{\"ok\":false,\"description\":\"chat not found\"}"});
        answers.add(new String[] {"500", "{}"});
        var provider = telegram();

        var refused = provider.send(new MessengerMessage("1", "x", "Open", "https://cms.example", "k"));
        var failed = provider.send(new MessengerMessage("1", "x", null, null, "k"));

        assertThat(lastBody.get()).isNotNull();
        assertThat(refused.isSuccess()).isFalse();
        assertThat(refused.errorCode()).isEqualTo("telegram_rejected");
        assertThat(refused.errorMessage()).isEqualTo("chat not found");
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.errorCode()).isEqualTo("telegram_send_failed");
    }

    @Test
    @DisplayName("Telegram: health follows getMe and an unreachable API is unhealthy")
    void telegramHealth() {
        answers.add(new String[] {"200", "{\"ok\":true}"});
        answers.add(new String[] {"200", "{\"ok\":false}"});
        var provider = telegram();

        assertThat(provider.checkHealth().isHealthy()).isTrue();
        assertThat(provider.checkHealth().isHealthy()).isFalse();
        var unreachable = new TelegramBotMessengerProvider(TOKEN, "http://127.0.0.1:1");
        assertThat(unreachable.checkHealth().isHealthy()).isFalse();
        assertThat(unreachable
                        .send(new MessengerMessage("1", "x", null, null, "k"))
                        .isSuccess())
                .isFalse();
    }

    @Test
    @DisplayName("SMTP: text and HTML go out as alternatives, attachments as parts, from the configured sender")
    void smtpMessageShapes() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        var provider = new SmtpMailProvider(sender, "noreply@cms.example", "SmartupCMS");

        var both = provider.send(new MailMessage("a@mailbox.test", "Both", "<b>hi</b>", "hi", null, "k1"));
        var html = provider.send(new MailMessage("a@mailbox.test", "Html", "<b>hi</b>", null, List.of(), "k2"));
        var none = provider.send(new MailMessage("a@mailbox.test", "Empty", null, null, null, "k3"));
        var attached = provider.send(new MailMessage(
                "a@mailbox.test",
                "Report",
                null,
                "see attached",
                List.of(new MailMessage.MailAttachment("r.csv", "text/csv", "a,b".getBytes(StandardCharsets.UTF_8))),
                "k4"));

        assertThat(List.of(both, html, none, attached))
                .allSatisfy(result -> assertThat(result.isSuccess()).isTrue());
        assertThat(both.messageId()).isEqualTo("k1");
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        org.mockito.Mockito.verify(sender, org.mockito.Mockito.times(4)).send(sent.capture());
        MimeMessage report = sent.getAllValues().get(3);
        assertThat(report.getFrom()[0].toString()).contains("noreply@cms.example");
        assertThat(report.getContent()).isInstanceOf(Multipart.class);
        assertThat(((Multipart) report.getContent()).getCount()).isEqualTo(2);
        assertThat(provider.getProviderCode()).isEqualTo("smtp");
    }

    @Test
    @DisplayName("SMTP: a refused send is a failure result; health of a server that does not answer is unhealthy")
    void smtpFailures() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("relay denied")).when(sender).send(any(MimeMessage.class));
        var provider = new SmtpMailProvider(sender, "noreply@cms.example", "SmartupCMS");

        var result = provider.send(new MailMessage("a@mailbox.test", "Subject", null, "text", null, "k"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("smtp_send_failed");
        assertThat(provider.checkHealth().isHealthy())
                .as("a sender that is not JavaMailSenderImpl")
                .isTrue();
        var impl = new JavaMailSenderImpl();
        impl.setHost("127.0.0.1");
        impl.setPort(1);
        assertThat(new SmtpMailProvider(impl, "noreply@cms.example", "SmartupCMS")
                        .checkHealth()
                        .isHealthy())
                .isFalse();
    }

    private TelegramBotMessengerProvider telegram() {
        return new TelegramBotMessengerProvider(
                TOKEN, "http://127.0.0.1:" + telegram.getAddress().getPort());
    }
}
