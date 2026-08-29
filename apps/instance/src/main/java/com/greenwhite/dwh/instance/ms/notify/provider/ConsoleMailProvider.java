package com.greenwhite.dwh.instance.ms.notify.provider;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.mail.MailMessage;
import com.greenwhite.dwh.spi.mail.MailProvider;
import com.greenwhite.dwh.spi.mail.MailSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Заглушка канала почты для разработки: пишет письмо в журнал и никуда его не шлёт.
 * Здоровье намеренно отрицательное — восстановление пароля через такой канал
 * не доходит до получателя, и знать об этом должна эксплуатация, а не пользователь.
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
        log.warn("[ЗАГЛУШКА ПОЧТЫ — НЕ ДОСТАВЛЕНО] Тема: {}, тело: {}",
                message.subject(),
                message.htmlBody() != null ? message.htmlBody() : message.textBody());

        return MailSendResult.success(UUID.randomUUID().toString(), 5);
    }

    @Override
    public ProviderHealth checkHealth() {
        return ProviderHealth.unhealthy(getProviderCode(),
                "Заглушка: письма не доставляются. Задайте spring.mail.host и dwh.providers.mail=smtp", 0);
    }
}
