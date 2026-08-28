package com.greenwhite.dwh.spi.mail;

import com.greenwhite.dwh.spi.common.ProviderHealth;

import java.util.List;

/**
 * Service Provider Interface for Email delivery providers (SMTP, SES, Mailgun, etc.).
 */
public interface MailProvider {

    String getProviderCode();

    MailSendResult send(MailMessage message);

    ProviderHealth checkHealth();
}
