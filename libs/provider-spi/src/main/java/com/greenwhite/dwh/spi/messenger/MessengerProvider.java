package com.greenwhite.dwh.spi.messenger;

import com.greenwhite.dwh.spi.common.ProviderHealth;

/**
 * Service Provider Interface for Instant Messengers (Telegram Bot API, WhatsApp, etc.).
 */
public interface MessengerProvider {

    String getProviderCode();

    MessengerSendResult send(MessengerMessage message);

    ProviderHealth checkHealth();
}
