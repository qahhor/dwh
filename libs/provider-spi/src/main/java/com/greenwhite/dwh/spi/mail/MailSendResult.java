package com.greenwhite.dwh.spi.mail;

public record MailSendResult(
        boolean isSuccess,
        String messageId,
        String errorCode,
        String errorMessage,
        long durationMs
) {
    public static MailSendResult success(String messageId, long durationMs) {
        return new MailSendResult(true, messageId, null, null, durationMs);
    }

    public static MailSendResult failure(String errorCode, String errorMessage, long durationMs) {
        return new MailSendResult(false, null, errorCode, errorMessage, durationMs);
    }
}
