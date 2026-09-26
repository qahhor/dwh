package com.smartup24.cms.spi.messenger;

public record MessengerMessage(
        String recipientChatId,
        String textMarkdown,
        String inlineButtonText,
        String inlineButtonUrl,
        String idempotencyKey
) {}
