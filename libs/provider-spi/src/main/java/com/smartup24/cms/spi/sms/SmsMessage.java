package com.smartup24.cms.spi.sms;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;

@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record SmsMessage(String recipientPhone, String text, String originator, String idempotencyKey) {}
