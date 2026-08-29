package com.greenwhite.dwh.instance.ms.notify.provider;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.messenger.MessengerMessage;
import com.greenwhite.dwh.spi.messenger.MessengerProvider;
import com.greenwhite.dwh.spi.messenger.MessengerSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Заглушка канала мессенджера для разработки: пишет сообщение в журнал,
 * чтобы разработчик увидел код OTP, и никуда его не отправляет.
 *
 * Раньше этот класс назывался TelegramMessengerProvider и объявлял код
 * «telegram», из-за чего система выглядела так, будто канал работает.
 * Здоровье провайдера намеренно отрицательное: канал не настроен, и
 * эксплуатация обязана это видеть, а не узнавать от пользователя.
 */
@Component
public class ConsoleMessengerProvider implements MessengerProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMessengerProvider.class);

    @Override
    public String getProviderCode() {
        return "console_messenger";
    }

    @Override
    public MessengerSendResult send(MessengerMessage message) {
        log.warn("[ЗАГЛУШКА МЕССЕНДЖЕРА — НЕ ДОСТАВЛЕНО] chat={}, текст: {}",
                message.recipientChatId(), message.textMarkdown());

        return MessengerSendResult.success(UUID.randomUUID().toString(), 1);
    }

    @Override
    public ProviderHealth checkHealth() {
        return ProviderHealth.unhealthy(getProviderCode(),
                "Заглушка: сообщения не доставляются. Задайте dwh.telegram.bot-token", 0);
    }
}
