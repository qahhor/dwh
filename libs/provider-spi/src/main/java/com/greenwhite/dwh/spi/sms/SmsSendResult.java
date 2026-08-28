package com.greenwhite.dwh.spi.sms;

public record SmsSendResult(
        boolean isSuccess,
        String externalMessageId,
        String errorCode,
        String errorMessage,
        long durationMs
) {
    public static SmsSendResult success(String externalMessageId, long durationMs) {
        return new SmsSendResult(true, externalMessageId, null, null, durationMs);
    }

    public static SmsSendResult failure(String errorCode, String errorMessage, long durationMs) {
        return new SmsSendResult(false, null, errorCode, errorMessage, durationMs);
    }
}
