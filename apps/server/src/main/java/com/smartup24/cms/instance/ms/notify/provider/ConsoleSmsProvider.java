package com.smartup24.cms.instance.ms.notify.provider;

import com.smartup24.cms.spi.common.ProviderHealth;
import com.smartup24.cms.spi.sms.SmsMessage;
import com.smartup24.cms.spi.sms.SmsProvider;
import com.smartup24.cms.spi.sms.SmsSendResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Development stub of the SMS channel: writes that a message was not delivered — the last two digits of the number
 * and the length, never the text with its code — and sends it nowhere. The number itself is personal data and is not
 * written to the log (CODE_STYLE).
 */
@Component
public class ConsoleSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSmsProvider.class);

    @Override
    public String getProviderCode() {
        return "console_sms";
    }

    @Override
    public SmsSendResult send(SmsMessage message) {
        // Never the text: it carries one-time codes (StubRecipients).
        log.warn(
                "sms_stub_not_delivered to={} length={}",
                StubRecipients.mask(message.recipientPhone()),
                StubRecipients.length(message.text()));

        return SmsSendResult.success(UUID.randomUUID().toString(), 3);
    }

    @Override
    public ProviderHealth checkHealth() {
        return ProviderHealth.unhealthy(
                getProviderCode(), "Stub: SMS are not delivered. Connect an operator gateway", 0);
    }
}
