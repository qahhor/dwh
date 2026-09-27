package com.smartup24.cms.instance.ms.notify.provider;

import com.smartup24.cms.spi.common.ProviderHealth;
import com.smartup24.cms.spi.sms.SmsMessage;
import com.smartup24.cms.spi.sms.SmsProvider;
import com.smartup24.cms.spi.sms.SmsSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Заглушка канала SMS для разработки: пишет сообщение в журнал и никуда его не шлёт.
 * Номер телефона — персональные данные, в журнал не попадает (CODE_STYLE).
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
        log.warn("[ЗАГЛУШКА SMS — НЕ ДОСТАВЛЕНО] текст: {}", message.text());

        return SmsSendResult.success(UUID.randomUUID().toString(), 3);
    }

    @Override
    public ProviderHealth checkHealth() {
        return ProviderHealth.unhealthy(getProviderCode(),
                "Заглушка: SMS не доставляются. Подключите шлюз оператора", 0);
    }
}
