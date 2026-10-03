package com.smartup24.cms.spi.sms;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;

@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record SmsSendResult(
        boolean isSuccess, String externalMessageId, String errorCode, String errorMessage, long durationMs) {
    public static SmsSendResult success(String externalMessageId, long durationMs) {
        return new SmsSendResult(true, externalMessageId, null, null, durationMs);
    }

    public static SmsSendResult failure(String errorCode, String errorMessage, long durationMs) {
        return new SmsSendResult(false, null, errorCode, errorMessage, durationMs);
    }
}
