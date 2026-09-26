package com.smartup24.cms.spi.sms;

public record SmsMessage(
        String recipientPhone,
        String text,
        String originator,
        String idempotencyKey
) {}
