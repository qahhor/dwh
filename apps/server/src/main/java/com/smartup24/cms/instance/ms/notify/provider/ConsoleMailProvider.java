package com.smartup24.cms.instance.ms.notify.provider;

import com.smartup24.cms.spi.common.ProviderHealth;
import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.mail.MailProvider;
import com.smartup24.cms.spi.mail.MailSendResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Development stub of the mail channel: writes the message to the log and sends it nowhere.
 * Its health is deliberately negative: password recovery through such a channel
 * never reaches the recipient, and operations must know this, not the user.
 */
@Component
public class ConsoleMailProvider implements MailProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMailProvider.class);

    @Override
    public String getProviderCode() {
        return "console_mail";
    }

    @Override
    public MailSendResult send(MailMessage message) {
        log.warn(
                "[ЗАГЛУШКА ПОЧТЫ — НЕ ДОСТАВЛЕНО] Тема: {}, тело: {}",
                message.subject(),
                message.htmlBody() != null ? message.htmlBody() : message.textBody());

        return MailSendResult.success(UUID.randomUUID().toString(), 5);
    }

    @Override
    public ProviderHealth checkHealth() {
        return ProviderHealth.unhealthy(
                getProviderCode(),
                "Заглушка: письма не доставляются. Задайте spring.mail.host и smc.providers.mail=smtp",
                0);
    }
}
