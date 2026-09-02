package com.greenwhite.dwh.instance.common.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Проверка каналов доставки при старте (FR-NOTIF-3, FR-NOTIF-4, FR-NOTIF-5).
 *
 * Почему это отдельный шаг, а не строка в журнале провайдера: заглушка канала
 * возвращает «отправлено», outbox помечает оповещение доставленным, и система
 * выглядит исправной, пока пользователь не сообщит, что письмо не пришло.
 * Восстановление пароля и OTP на таком канале не работают вовсе.
 *
 * Сетевых проверок здесь нет намеренно: старт экземпляра не должен зависеть
 * от доступности почтового шлюза. Смотрим только на то, какой провайдер
 * активен — этого достаточно, чтобы отличить настроенный канал от заглушки.
 */
@Component
public class NotificationChannelStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(NotificationChannelStartupCheck.class);
    private static final String STUB_PREFIX = "console_";

    private final ProviderRegistry providerRegistry;

    public NotificationChannelStartupCheck(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportOnStartup() {
        var stubs = findStubChannels();
        if (stubs.isEmpty()) {
            log.info("Каналы доставки настроены: почта={}, SMS={}, мессенджер={}",
                    providerRegistry.getActiveMailProvider().getProviderCode(),
                    providerRegistry.getActiveSmsProvider().getProviderCode(),
                    providerRegistry.getActiveMessengerProvider().getProviderCode());
            return;
        }
        log.warn("КАНАЛЫ ДОСТАВКИ НЕ НАСТРОЕНЫ: {}. Восстановление пароля и OTP "
                        + "по этим каналам не дойдут до получателя — сообщения только пишутся в журнал.",
                String.join(", ", stubs));
    }

    /** Каналы, на которых активна заглушка. Пустой список — все каналы настроены. */
    public List<String> findStubChannels() {
        List<String> stubs = new ArrayList<>();
        addIfStub(stubs, "почта", providerRegistry.getActiveMailProvider().getProviderCode());
        addIfStub(stubs, "SMS", providerRegistry.getActiveSmsProvider().getProviderCode());
        addIfStub(stubs, "мессенджер", providerRegistry.getActiveMessengerProvider().getProviderCode());
        return stubs;
    }

    private static void addIfStub(List<String> stubs, String channel, String providerCode) {
        if (providerCode.startsWith(STUB_PREFIX)) {
            stubs.add(channel + " (" + providerCode + ")");
        }
    }
}
