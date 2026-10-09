package com.smartup24.cms.instance.ms.notify.sse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registry of open SSE connections (FR-NOTIF-2, FR-API-5).
 * One user may have several tabs open, hence a list per user.
 *
 * The registry holds the streams of this node only. Notifications created on another node of a cluster reach them
 * through {@link MsSsePublisher}, which relays them over PostgreSQL LISTEN/NOTIFY (ADR-0025, section 2.5).
 */
@Component
public class MsSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(MsSseRegistry.class);

    private final Map<Long, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();
    private final AtomicInteger openConnections = new AtomicInteger();

    private final long timeoutMs;
    private final int maxPerUser;

    public MsSseRegistry(
            @Value("${smc.sse.timeout-ms:1800000}") long timeoutMs,
            @Value("${smc.sse.max-connections-per-user:5}") int maxPerUser) {
        this.timeoutMs = timeoutMs;
        this.maxPerUser = maxPerUser;
    }

    /**
     * Opens a stream for the user. The browser EventSource reconnects by itself,
     * so a timeout is a normal completion, not an error.
     */
    public SseEmitter subscribe(Long userId) {
        List<SseEmitter> userEmitters = emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

        // Guard against thread exhaustion: a user has a finite number of tabs,
        // anything above the limit is almost certainly a leak on the client.
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

        // The first event is sent at once: it confirms to the client that the stream is alive
        // and makes proxies send the headers without buffering the response.
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            remove(userId, emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** Sends the event to all connections of the user and removes dead ones. */
    public void send(Long userId, String eventName, Object payload) {
        List<SseEmitter> userEmitters = emittersByUser.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return; // the user is offline and will see the notification at the next sign-in
        }
        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                // A dropped connection is normal (a tab was closed, a laptop went to sleep)
                remove(userId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /** Keep-alive: without traffic, proxies and load balancers drop the connection. */
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

    /** Whether the user has a stream open on this node. */
    public boolean hasConnections(Long userId) {
        List<SseEmitter> userEmitters = emittersByUser.get(userId);
        return userEmitters != null && !userEmitters.isEmpty();
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
        log.debug("sse_connection_closed user={} open={}", userId, openConnections.get());
    }
}
