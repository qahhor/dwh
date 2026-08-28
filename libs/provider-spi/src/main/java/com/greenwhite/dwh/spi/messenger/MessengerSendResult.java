package com.greenwhite.dwh.spi.messenger;

public record MessengerSendResult(
        boolean isSuccess,
        String externalMessageId,
        String errorCode,
        String errorMessage,
        long durationMs
) {
    public static MessengerSendResult success(String externalMessageId, long durationMs) {
        return new MessengerSendResult(true, externalMessageId, null, null, durationMs);
    }

    public static MessengerSendResult failure(String errorCode, String errorMessage, long durationMs) {
        return new MessengerSendResult(false, null, errorCode, errorMessage, durationMs);
    }
}
