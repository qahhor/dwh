package com.smartup24.cms.spi.mail;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.spi.common.ProviderHealth;

/**
 * Service Provider Interface for Email delivery providers (SMTP, SES, Mailgun, etc.).
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public interface MailProvider {

    String getProviderCode();

    MailSendResult send(MailMessage message);

    ProviderHealth checkHealth();
}
