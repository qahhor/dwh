package com.greenwhite.dwh.instance.ms.notify.provider;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.mail.MailMessage;
import com.greenwhite.dwh.spi.mail.MailProvider;
import com.greenwhite.dwh.spi.mail.MailSendResult;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ByteArrayResource;

/**
 * FR-NOTIF-3: доставка писем по SMTP.
 *
 * Бин создаётся только когда {@code spring.mail.host} задан непустым — иначе поднимать
 * нечего и активным останется консольная заглушка. Выбор активного провайдера —
 * за {@code dwh.providers.mail} (ADR-0011), этот класс лишь умеет отправлять.
 *
 * Ошибка отправки не бросается наружу: у вызывающего кода (outbox оповещений)
 * есть собственный retry, и он опирается на {@link MailSendResult#isSuccess()}.
 */
@Component
@ConditionalOnExpression("'${spring.mail.host:}'.trim().length() > 0")
public class SmtpMailProvider implements MailProvider {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailProvider.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String fromName;

    public SmtpMailProvider(JavaMailSender mailSender,
                            @Value("${dwh.mail.from:no-reply@localhost}") String from,
                            @Value("${dwh.mail.from-name:DWH Platform}") String fromName) {
        this.mailSender = mailSender;
        this.from = from;
        this.fromName = fromName;
    }

    @Override
    public String getProviderCode() {
        return "smtp";
    }

    @Override
    public MailSendResult send(MailMessage message) {
        long startedAt = System.nanoTime();
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            boolean hasAttachments = message.attachments() != null && !message.attachments().isEmpty();
            boolean hasAlternativeBodies = message.htmlBody() != null && message.textBody() != null;
            // Два представления письма или вложения существуют только в multipart:
            // без этого флага setText(text, html) бросает IllegalStateException.
            var helper = new MimeMessageHelper(mime, hasAttachments || hasAlternativeBodies, "UTF-8");

            helper.setFrom(from, fromName);
            helper.setTo(message.recipientEmail());
            helper.setSubject(message.subject());

            // Письмо уходит в двух представлениях: текст для почтовых клиентов
            // без HTML и для антиспам-фильтров, HTML — для остальных.
            if (message.htmlBody() != null && message.textBody() != null) {
                helper.setText(message.textBody(), message.htmlBody());
            } else if (message.htmlBody() != null) {
                helper.setText(message.htmlBody(), true);
            } else {
                helper.setText(message.textBody() != null ? message.textBody() : "", false);
            }

            if (hasAttachments) {
                for (var attachment : message.attachments()) {
                    helper.addAttachment(attachment.filename(),
                            new ByteArrayResource(attachment.content()), attachment.contentType());
                }
            }

            mailSender.send(mime);

            String messageId = mime.getMessageID();
            return MailSendResult.success(messageId != null ? messageId : message.idempotencyKey(),
                    elapsedMs(startedAt));

        } catch (Exception ex) {
            // Адрес получателя — персональные данные, в журнал не пишем (CODE_STYLE, логи без ПДн).
            log.warn("SMTP: письмо не отправлено, тема '{}': {}", message.subject(), ex.getMessage());
            return MailSendResult.failure("smtp_send_failed", ex.getMessage(), elapsedMs(startedAt));
        }
    }

    @Override
    public ProviderHealth checkHealth() {
        long startedAt = System.nanoTime();
        if (!(mailSender instanceof JavaMailSenderImpl impl)) {
            return ProviderHealth.healthy(getProviderCode(), elapsedMs(startedAt));
        }
        try {
            impl.testConnection();
            return ProviderHealth.healthy(getProviderCode(), elapsedMs(startedAt));
        } catch (Exception ex) {
            return ProviderHealth.unhealthy(getProviderCode(),
                    "SMTP недоступен: " + ex.getMessage(), elapsedMs(startedAt));
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
