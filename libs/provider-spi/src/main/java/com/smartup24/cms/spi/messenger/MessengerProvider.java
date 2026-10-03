package com.smartup24.cms.spi.messenger;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.spi.common.ProviderHealth;

/**
 * Service Provider Interface for Instant Messengers (Telegram Bot API, WhatsApp, etc.).
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public interface MessengerProvider {

    String getProviderCode();

    MessengerSendResult send(MessengerMessage message);

    ProviderHealth checkHealth();
}
