package com.smartup24.cms.instance.ms.notify.provider;

import com.smartup24.cms.spi.common.ProviderHealth;
import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.mail.MailProvider;
import com.smartup24.cms.spi.mail.MailSendResult;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * FR-NOTIF-3: mail delivery over SMTP.
 *
 * The bean is created only when {@code spring.mail.host} is set and not blank; otherwise
 * there is nothing to start and the console stub stays active. The active provider is chosen
 * by {@code smc.providers.mail} (ADR-0011); this class only knows how to send.
 *
 * A send failure is not thrown to the caller: the calling code (the notification outbox)
 * has its own retry, and it relies on {@link MailSendResult#isSuccess()}.
 */
@Component
@ConditionalOnExpression("'${spring.mail.host:}'.trim().length() > 0")
public class SmtpMailProvider implements MailProvider {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailProvider.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String fromName;

    public SmtpMailProvider(
            JavaMailSender mailSender,
            @Value("${smc.mail.from:no-reply@localhost}") String from,
            @Value("${smc.mail.from-name:SmartupCMS}") String fromName) {
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
            boolean hasAttachments =
                    message.attachments() != null && !message.attachments().isEmpty();
            boolean hasAlternativeBodies = message.htmlBody() != null && message.textBody() != null;
            // Two representations of a message, or attachments, exist only in multipart:
            // without this flag setText(text, html) throws IllegalStateException.
            var helper = new MimeMessageHelper(mime, hasAttachments || hasAlternativeBodies, "UTF-8");

            helper.setFrom(from, fromName);
            helper.setTo(message.recipientEmail());
            helper.setSubject(message.subject());

            // The message goes out in two representations: plain text for mail clients
            // without HTML and for spam filters, HTML for everyone else.
            if (message.htmlBody() != null && message.textBody() != null) {
                helper.setText(message.textBody(), message.htmlBody());
            } else if (message.htmlBody() != null) {
                helper.setText(message.htmlBody(), true);
            } else {
                helper.setText(message.textBody() != null ? message.textBody() : "", false);
            }

            if (hasAttachments) {
                for (var attachment : message.attachments()) {
                    helper.addAttachment(
                            attachment.filename(),
                            new ByteArrayResource(attachment.content()),
                            attachment.contentType());
                }
            }

            mailSender.send(mime);

            String messageId = mime.getMessageID();
            return MailSendResult.success(
                    messageId != null ? messageId : message.idempotencyKey(), elapsedMs(startedAt));

        } catch (Exception ex) {
            // The recipient address is personal data and is not logged (CODE_STYLE, no personal data in logs).
            log.warn("smtp_send_failed subject={} error={}", message.subject(), ex.getMessage());
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
            return ProviderHealth.unhealthy(
                    getProviderCode(), "SMTP is unavailable: " + ex.getMessage(), elapsedMs(startedAt));
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
