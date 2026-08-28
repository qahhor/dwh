package com.greenwhite.dwh.spi.sms;

public record SmsMessage(
        String recipientPhone,
        String text,
        String originator,
        String idempotencyKey
) {}
