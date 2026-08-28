package com.greenwhite.dwh.spi.messenger;

public record MessengerMessage(
        String recipientChatId,
        String textMarkdown,
        String inlineButtonText,
        String inlineButtonUrl,
        String idempotencyKey
) {}
