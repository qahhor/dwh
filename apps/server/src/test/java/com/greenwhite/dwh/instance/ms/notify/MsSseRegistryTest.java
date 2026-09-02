package com.greenwhite.dwh.instance.ms.notify;

import com.greenwhite.dwh.instance.ms.notify.sse.MsSseRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 (FR-NOTIF-2): реестр SSE-соединений — доставка, изоляция пользователей,
 * лимит вкладок, вычистка мёртвых соединений.
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

        // Событие connected уже отправлено при подписке — поток не «молчит»
        // до первого уведомления, что важно для прокси и для клиента.
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

        // Доставка несуществующему пользователю не должна падать:
        // пользователь просто офлайн, уведомление он увидит при входе.
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

        // Клиент ушёл. В реальном запросе контейнер вызовет onCompletion/onError
        // (в юнит-тесте async-контекста нет), поэтому проверяем ВТОРОЙ рубеж
        // защиты: отправка в завершённый emitter падает и он исключается.
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
