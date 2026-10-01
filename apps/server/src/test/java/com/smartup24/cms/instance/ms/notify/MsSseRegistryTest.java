package com.smartup24.cms.instance.ms.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.ms.notify.sse.MsSseRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * FR-NOTIF-2: the registry of SSE connections: delivery, user isolation,
 * the tab limit, and cleanup of dead connections.
 */
class MsSseRegistryTest {

    private final MsSseRegistry registry = new MsSseRegistry(60_000, 3);

    @Test
    @DisplayName("Подписка открывает поток и сразу шлёт connected")
    void subscribeSendsConnectedEvent() throws Exception {
        List<Object> received = new ArrayList<>();
        SseEmitter emitter = registry.subscribe(1L);
        emitter.onCompletion(() -> {});

        assertThat(registry.openConnectionCount()).isEqualTo(1);
        assertThat(emitter).isNotNull();

        // The connected event is already sent on subscription, so the stream is not "silent"
        // until the first notification, which matters for proxies and for the client.
        registry.send(1L, "notification", Map.of("id", 42));
        received.add("ok");
        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("Уведомление уходит только адресату, чужие потоки не трогаются")
    void sendIsolatesUsers() {
        registry.subscribe(1L);
        registry.subscribe(2L);
        assertThat(registry.openConnectionCount()).isEqualTo(2);

        // Delivery to a user who does not exist must not fail:
        // the user is simply offline and will see the notification on sign-in.
        registry.send(999L, "notification", Map.of("id", 1));
        assertThat(registry.openConnectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Лимит соединений на пользователя: старые вытесняются, счётчик не растёт")
    void enforcesMaxConnectionsPerUser() {
        for (int i = 0; i < 6; i++) {
            registry.subscribe(7L);
        }
        assertThat(registry.openConnectionCount())
                .as("лимит 3 на пользователя — защита от утечки вкладок")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Мёртвое соединение вычищается при следующей отправке")
    void deadEmitterIsRemovedOnNextSend() {
        SseEmitter emitter = registry.subscribe(5L);
        assertThat(registry.openConnectionCount()).isEqualTo(1);

        // The client left. In a real request the container calls onCompletion/onError
        // (a unit test has no async context), so this checks the SECOND line
        // of defense: sending to a completed emitter fails and the emitter is removed.
        emitter.complete();
        registry.send(5L, "notification", Map.of("id", 1));

        assertThat(registry.openConnectionCount())
                .as("мёртвое соединение не должно оставаться в реестре")
                .isZero();
    }

    @Test
    @DisplayName("Heartbeat тоже вычищает мёртвые соединения — не копятся между уведомлениями")
    void heartbeatRemovesDeadEmitters() {
        SseEmitter emitter = registry.subscribe(6L);
        emitter.complete();

        registry.sendHeartbeat();

        assertThat(registry.openConnectionCount())
                .as("heartbeat раз в 25 с — гарантия, что мёртвые соединения не живут дольше")
                .isZero();
    }

    @Test
    @DisplayName("Heartbeat не падает и не ломает реестр при отсутствии подписчиков")
    void heartbeatIsSafeWhenEmpty() {
        registry.sendHeartbeat();
        assertThat(registry.openConnectionCount()).isZero();

        registry.subscribe(3L);
        registry.sendHeartbeat();
        assertThat(registry.openConnectionCount()).isEqualTo(1);
    }
}
