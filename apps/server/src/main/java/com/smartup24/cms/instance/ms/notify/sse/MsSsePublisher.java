package com.smartup24.cms.instance.ms.notify.sse;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers notifications to open SSE streams.
 *
 * The listener fires AFTER the transaction commits (AFTER_COMMIT): if the push
 * went out inside the transaction, the client could request the list and not see
 * the notification, or see one that was later rolled back.
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
        registry.send(
                event.userId(),
                "notification",
                Map.of(
                        "id", n.id(),
                        "type", n.type(),
                        "title", n.title(),
                        "body", n.body(),
                        "formLink", n.formLink() != null ? n.formLink() : "",
                        "createdAt", n.createdAt().toString()));
    }

    /** Keep-alive: proxies drop connections without traffic (usually after 60 s). */
    @Scheduled(fixedDelayString = "${dwh.sse.heartbeat-ms:25000}")
    public void heartbeat() {
        registry.sendHeartbeat();
    }
}
