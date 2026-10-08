package com.smartup24.cms.instance.ms.notify.provider;

import com.smartup24.cms.spi.common.ProviderHealth;
import com.smartup24.cms.spi.messenger.MessengerMessage;
import com.smartup24.cms.spi.messenger.MessengerProvider;
import com.smartup24.cms.spi.messenger.MessengerSendResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Development stub of the messenger channel: writes that a message was not delivered — the masked chat and the
 * length, never the text with its code — and sends it nowhere. A developer reads codes from a real channel (the
 * local mail catcher of the development stack), not from the log.
 *
 * This class used to be called TelegramMessengerProvider and declared the code
 * "telegram", which made the system look as if the channel worked.
 * The provider health is deliberately negative: the channel is not configured, and
 * operations must see this rather than learn it from users.
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
        // Never the text: it carries one-time codes and links (StubRecipients).
        log.warn(
                "messenger_stub_not_delivered chat={} length={}",
                StubRecipients.mask(message.recipientChatId()),
                StubRecipients.length(message.textMarkdown()));

        return MessengerSendResult.success(UUID.randomUUID().toString(), 1);
    }

    @Override
    public ProviderHealth checkHealth() {
        return ProviderHealth.unhealthy(
                getProviderCode(), "Stub: messages are not delivered. Set smc.telegram.bot-token", 0);
    }
}
