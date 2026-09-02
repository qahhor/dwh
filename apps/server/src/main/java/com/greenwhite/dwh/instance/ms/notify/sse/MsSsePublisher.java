package com.greenwhite.dwh.instance.ms.notify.sse;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Доставка уведомлений в открытые SSE-потоки.
 *
 * Слушатель срабатывает ПОСЛЕ коммита транзакции (AFTER_COMMIT): если бы push
 * уходил внутри транзакции, клиент мог бы запросить список и не увидеть
 * уведомление — или увидеть то, что затем откатилось.
 */
@Component
@Profile("!migrate")
public class MsSsePublisher {

    private final MsSseRegistry registry;

    public MsSsePublisher(MsSseRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(MsNotificationCreatedEvent event) {
        var n = event.notification();
        registry.send(event.userId(), "notification", Map.of(
                "id", n.id(),
                "type", n.type(),
                "title", n.title(),
                "body", n.body(),
                "formLink", n.formLink() != null ? n.formLink() : "",
                "createdAt", n.createdAt().toString()
        ));
    }

    /** Keep-alive: прокси рвут соединения без трафика (обычно 60 с). */
    @Scheduled(fixedDelayString = "${dwh.sse.heartbeat-ms:25000}")
    public void heartbeat() {
        registry.sendHeartbeat();
    }
}
