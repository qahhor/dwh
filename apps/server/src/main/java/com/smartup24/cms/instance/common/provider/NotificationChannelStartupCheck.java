package com.smartup24.cms.instance.common.provider;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Checks delivery channels at startup (FR-NOTIF-3, FR-NOTIF-4, FR-NOTIF-5).
 *
 * Why this is a separate step rather than a line in the provider's log: a stub channel
 * returns "sent", the outbox marks the notification delivered, and the system
 * looks healthy until a user reports that the email never arrived.
 * Password recovery and OTP do not work at all on such a channel.
 *
 * There are deliberately no network checks here: instance startup must not depend
 * on the mail gateway being reachable. Only the active provider is inspected,
 * which is enough to tell a configured channel from a stub.
 */
@Component
public class NotificationChannelStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(NotificationChannelStartupCheck.class);
    private static final String STUB_PREFIX = "console_";

    private final ProviderRegistry providerRegistry;

    public NotificationChannelStartupCheck(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportOnStartup() {
        var stubs = findStubChannels();
        if (stubs.isEmpty()) {
            log.info(
                    "delivery_channels_configured mail={} sms={} messenger={}",
                    providerRegistry.getActiveMailProvider().getProviderCode(),
                    providerRegistry.getActiveSmsProvider().getProviderCode(),
                    providerRegistry.getActiveMessengerProvider().getProviderCode());
            return;
        }
        log.warn(
                "delivery_channels_not_configured channels={}: password recovery and one-time codes over them"
                        + " never reach the recipient, the messages are only written to the log",
                String.join(", ", stubs));
    }

    /** Channels where a stub is active. An empty list means every channel is configured. */
    public List<String> findStubChannels() {
        List<String> stubs = new ArrayList<>();
        addIfStub(stubs, "mail", providerRegistry.getActiveMailProvider().getProviderCode());
        addIfStub(stubs, "SMS", providerRegistry.getActiveSmsProvider().getProviderCode());
        addIfStub(
                stubs,
                "messenger",
                providerRegistry.getActiveMessengerProvider().getProviderCode());
        return stubs;
    }

    private static void addIfStub(List<String> stubs, String channel, String providerCode) {
        if (providerCode.startsWith(STUB_PREFIX)) {
            stubs.add(channel + " (" + providerCode + ")");
        }
    }
}
