package com.smartup24.cms.spi.mail;

import com.smartup24.cms.spi.common.ProviderHealth;

import java.util.List;

/**
 * Service Provider Interface for Email delivery providers (SMTP, SES, Mailgun, etc.).
 */
public interface MailProvider {

    String getProviderCode();

    MailSendResult send(MailMessage message);

    ProviderHealth checkHealth();
}
