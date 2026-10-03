package com.smartup24.cms.spi.mail;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;

@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record MailSendResult(
        boolean isSuccess, String messageId, String errorCode, String errorMessage, long durationMs) {
    public static MailSendResult success(String messageId, long durationMs) {
        return new MailSendResult(true, messageId, null, null, durationMs);
    }

    public static MailSendResult failure(String errorCode, String errorMessage, long durationMs) {
        return new MailSendResult(false, null, errorCode, errorMessage, durationMs);
    }
}
