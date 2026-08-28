package com.greenwhite.dwh.spi.sms;

import com.greenwhite.dwh.spi.common.ProviderHealth;

import java.util.concurrent.CompletableFuture;

/**
 * Service Provider Interface for SMS delivery providers (PlayMobile, Eskiz, Infobip, etc.).
 */
public interface SmsProvider {

    /**
     * Unique identifier code of the provider implementation.
     */
    String getProviderCode();

    /**
     * Send SMS message synchronously.
     */
    SmsSendResult send(SmsMessage message);

    /**
     * Send SMS message asynchronously.
     */
    default CompletableFuture<SmsSendResult> sendAsync(SmsMessage message) {
        return CompletableFuture.supplyAsync(() -> send(message));
    }

    /**
     * Check provider connectivity and balance/health.
     */
    ProviderHealth checkHealth();
}
