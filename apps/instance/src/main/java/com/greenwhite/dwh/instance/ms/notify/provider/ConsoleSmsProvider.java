package com.greenwhite.dwh.instance.ms.notify.provider;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.sms.SmsMessage;
import com.greenwhite.dwh.spi.sms.SmsProvider;
import com.greenwhite.dwh.spi.sms.SmsSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ConsoleSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSmsProvider.class);

    @Override
    public String getProviderCode() {
        return "console_sms";
    }

    @Override
    public SmsSendResult send(SmsMessage message) {
        log.info("[SMS OUTBOX] Sending SMS to <{}>: Text='{}'", message.recipientPhone(), message.text());

        return SmsSendResult.success(UUID.randomUUID().toString(), 3);
    }

    @Override
    public ProviderHealth checkHealth() {
        return ProviderHealth.healthy(getProviderCode(), 1);
    }
}
