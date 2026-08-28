package com.greenwhite.dwh.instance.ms.notify.provider;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.mail.MailMessage;
import com.greenwhite.dwh.spi.mail.MailProvider;
import com.greenwhite.dwh.spi.mail.MailSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ConsoleMailProvider implements MailProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMailProvider.class);

    @Override
    public String getProviderCode() {
        return "console_mail";
    }

    @Override
    public MailSendResult send(MailMessage message) {
        log.info("[MAIL OUTBOX] Sending to <{}>: Subject='{}', Body='{}'",
                message.recipientEmail(), message.subject(),
                message.htmlBody() != null ? message.htmlBody() : message.textBody());

        return MailSendResult.success(UUID.randomUUID().toString(), 5);
    }

    @Override
    public ProviderHealth checkHealth() {
        return ProviderHealth.healthy(getProviderCode(), 1);
    }
}
