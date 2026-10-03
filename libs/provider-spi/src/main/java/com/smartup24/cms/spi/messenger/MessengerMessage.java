package com.smartup24.cms.spi.messenger;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;

@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record MessengerMessage(
        String recipientChatId,
        String textMarkdown,
        String inlineButtonText,
        String inlineButtonUrl,
        String idempotencyKey) {}
