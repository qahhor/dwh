package com.smartup24.cms.spi.messenger;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;

@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record MessengerSendResult(
        boolean isSuccess, String externalMessageId, String errorCode, String errorMessage, long durationMs) {
    public static MessengerSendResult success(String externalMessageId, long durationMs) {
        return new MessengerSendResult(true, externalMessageId, null, null, durationMs);
    }

    public static MessengerSendResult failure(String errorCode, String errorMessage, long durationMs) {
        return new MessengerSendResult(false, null, errorCode, errorMessage, durationMs);
    }
}
