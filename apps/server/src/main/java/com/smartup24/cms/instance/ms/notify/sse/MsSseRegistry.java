package com.smartup24.cms.instance.ms.notify.sse;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext.KauthPrincipal;
import java.util.ArrayList;
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

    private final Map<Long, List<Connection>> emittersByUser = new ConcurrentHashMap<>();
    private final AtomicInteger openConnections = new AtomicInteger();

    private final long timeoutMs;
    private final int maxPerUser;
    private final MsSseAccess access;

    public MsSseRegistry(
            @Value("${smc.sse.timeout-ms:1800000}") long timeoutMs,
            @Value("${smc.sse.max-connections-per-user:5}") int maxPerUser,
            MsSseAccess access) {
        if (maxPerUser < 1) throw new IllegalArgumentException("SSE connection limit must be positive");
        this.timeoutMs = timeoutMs;
        this.maxPerUser = maxPerUser;
        this.access = access;
    }

    /**
     * Opens a stream for the user. The browser EventSource reconnects by itself,
     * so a timeout is a normal completion, not an error.
     */
    public SseEmitter subscribe(KauthPrincipal principal) {
        access.requireCurrent(principal);
        Long userId = principal.userId();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Connection connection = new Connection(principal, emitter);
        emitter.onCompletion(() -> remove(userId, connection));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(userId, connection);
        });
        emitter.onError(e -> remove(userId, connection));
        List<Connection> evicted = new ArrayList<>();
        emittersByUser.compute(userId, (id, existing) -> {
            List<Connection> connections = existing == null ? new CopyOnWriteArrayList<>() : existing;
            while (connections.size() >= maxPerUser) {
                evicted.add(connections.removeFirst());
                openConnections.decrementAndGet();
            }
            connections.add(connection);
            openConnections.incrementAndGet();
            return connections;
        });
        evicted.forEach(old -> old.emitter().complete());
        // The first event is sent at once: it confirms to the client that the stream is alive
        // and makes proxies send the headers without buffering the response.
        send(connection, SseEmitter.event().name("connected").data("ok"));
        return emitter;
    }

    /** Sends the event to all connections of the user and removes dead ones. */
    public void send(Long userId, String eventName, Object payload) {
        List<Connection> userEmitters = emittersByUser.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return; // the user is offline and will see the notification at the next sign-in
        }
        for (Connection connection : userEmitters) {
            send(connection, SseEmitter.event().name(eventName).data(payload));
        }
    }

    /** Keep-alive: without traffic, proxies and load balancers drop the connection. */
    public void sendHeartbeat() {
        emittersByUser.forEach((userId, list) -> {
            for (Connection connection : list) {
                send(connection, SseEmitter.event().comment("ping"));
            }
        });
    }

    /** Whether the user has a stream open on this node. */
    public boolean hasConnections(Long userId) {
        List<Connection> userEmitters = emittersByUser.get(userId);
        return userEmitters != null && !userEmitters.isEmpty();
    }

    public int openConnectionCount() {
        return openConnections.get();
    }

    private void send(Connection connection, SseEmitter.SseEventBuilder event) {
        Long userId = connection.principal().userId();
        try {
            access.requireCurrent(connection.principal());
        } catch (ApiException revoked) {
            log.debug("sse_access_revoked user={} code={}", userId, revoked.getErrorCode());
            remove(userId, connection);
            connection.emitter().complete();
            return;
        } catch (RuntimeException unavailable) {
            log.warn("sse_authorization_failed user={}", userId, unavailable);
            remove(userId, connection);
            connection.emitter().complete();
            return;
        }
        try {
            connection.emitter().send(event);
        } catch (Exception failure) {
            remove(userId, connection);
            connection.emitter().completeWithError(failure);
        }
    }

    private void remove(Long userId, Connection connection) {
        emittersByUser.computeIfPresent(userId, (id, connections) -> {
            if (connections.remove(connection)) openConnections.decrementAndGet();
            return connections.isEmpty() ? null : connections;
        });
        log.debug("sse_connection_closed user={} open={}", userId, openConnections.get());
    }

    private record Connection(KauthPrincipal principal, SseEmitter emitter) {}
}
