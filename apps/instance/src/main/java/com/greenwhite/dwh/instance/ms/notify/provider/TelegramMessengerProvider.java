package com.greenwhite.dwh.instance.ms.notify.provider;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.messenger.MessengerMessage;
import com.greenwhite.dwh.spi.messenger.MessengerProvider;
import com.greenwhite.dwh.spi.messenger.MessengerSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TelegramMessengerProvider implements MessengerProvider {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessengerProvider.class);

    @Override
    public String getProviderCode() {
        return "telegram";
    }

    @Override
    public MessengerSendResult send(MessengerMessage message) {
        log.info("[TELEGRAM OUTBOX] Sending to chat <{}>: Text='{}'", message.recipientChatId(), message.textMarkdown());

        return MessengerSendResult.success(UUID.randomUUID().toString(), 10);
    }

    @Override
    public ProviderHealth checkHealth() {
        return ProviderHealth.healthy(getProviderCode(), 1);
    }
}
