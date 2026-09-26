package com.smartup24.cms.spi.messenger;

import com.smartup24.cms.spi.common.ProviderHealth;

/**
 * Service Provider Interface for Instant Messengers (Telegram Bot API, WhatsApp, etc.).
 */
public interface MessengerProvider {

    String getProviderCode();

    MessengerSendResult send(MessengerMessage message);

    ProviderHealth checkHealth();
}
