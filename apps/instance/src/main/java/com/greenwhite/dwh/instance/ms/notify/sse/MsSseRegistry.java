package com.greenwhite.dwh.instance.ms.notify.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Реестр открытых SSE-соединений (FR-NOTIF-2, FR-API-5).
 * У одного пользователя может быть несколько вкладок — отсюда список на пользователя.
 *
 * Область применения: один инстанс приложения на экземпляр клиента (ТЗ-01 разд. 3).
 * При переходе на несколько нод (фаза P) доставку между нодами обеспечит
 * PostgreSQL LISTEN/NOTIFY — точка расширения обозначена в ADR-0009 разд. 3.
 */
@Component
public class MsSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(MsSseRegistry.class);

    private final Map<Long, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();
    private final AtomicInteger openConnections = new AtomicInteger();

    private final long timeoutMs;
    private final int maxPerUser;

    public MsSseRegistry(@Value("${dwh.sse.timeout-ms:1800000}") long timeoutMs,
                         @Value("${dwh.sse.max-connections-per-user:5}") int maxPerUser) {
        this.timeoutMs = timeoutMs;
        this.maxPerUser = maxPerUser;
    }

    /**
     * Открывает поток для пользователя. Браузерный EventSource переподключается сам,
     * поэтому истечение таймаута — штатное завершение, а не ошибка.
     */
    public SseEmitter subscribe(Long userId) {
        List<SseEmitter> userEmitters =
                emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

        // Защита от исчерпания потоков: вкладок у пользователя конечное число,
        // всё сверх лимита — почти наверняка утечка на клиенте.
        while (userEmitters.size() >= maxPerUser) {
            SseEmitter oldest = userEmitters.isEmpty() ? null : userEmitters.get(0);
            if (oldest == null) break;
            userEmitters.remove(oldest);
            openConnections.decrementAndGet();
            oldest.complete();
        }

        SseEmitter emitter = new SseEmitter(timeoutMs);
        userEmitters.add(emitter);
        openConnections.incrementAndGet();

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(userId, emitter);
        });
        emitter.onError(e -> remove(userId, emitter));

        // Первое событие сразу: подтверждает клиенту, что поток жив,
        // и заставляет прокси отдать заголовки, не буферизуя ответ.
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            remove(userId, emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** Рассылает событие всем соединениям пользователя. Мёртвые — вычищает. */
    public void send(Long userId, String eventName, Object payload) {
        List<SseEmitter> userEmitters = emittersByUser.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return; // пользователь офлайн — уведомление он увидит при следующем входе
        }
        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                // Обрыв соединения — норма (закрыли вкладку, уснул ноутбук)
                remove(userId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /** Keep-alive: без трафика прокси и балансировщики рвут соединение. */
    public void sendHeartbeat() {
        emittersByUser.forEach((userId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(userId, emitter);
                    emitter.completeWithError(e);
                }
            }
        });
    }

    public int openConnectionCount() {
        return openConnections.get();
    }

    private void remove(Long userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emittersByUser.get(userId);
        if (userEmitters != null && userEmitters.remove(emitter)) {
            openConnections.decrementAndGet();
            if (userEmitters.isEmpty()) {
                emittersByUser.remove(userId, userEmitters);
            }
        }
        log.debug("SSE-соединение закрыто, user={}, осталось открытых={}", userId, openConnections.get());
    }
}
