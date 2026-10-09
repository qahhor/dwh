package com.smartup24.cms.instance.config.cache;

import com.smartup24.cms.instance.common.cluster.ClusterNotices;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps the caches of every node of a cluster in step (plan 10/10, item 3.13, ADR-0025). A node that clears a cache
 * sends {@code NOTIFY smc_cache} inside its transaction, which PostgreSQL delivers only when the transaction commits
 * and drops with a rollback, so a rolled-back change tells nobody; every other node, listening on a connection of its
 * own, clears the same cache. A notice may also name a node-local copy that is not a cache (the search settings): the
 * handlers registered for that name run instead. After a lost connection the node clears all its caches and runs all
 * handlers, since it may have missed a notice, and listens again. A message ({@link #send}) rides the same channel
 * with its topic marked by {@value #MESSAGE}; a message missed while the node did not listen is not replayed.
 */
public class CacheInvalidations implements SmartLifecycle, ClusterNotices {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidations.class);

    static final String CHANNEL = "smc_cache";
    static final String ALL = "*";
    /** The mark of a message topic in a payload: no cache name starts with it. */
    static final String MESSAGE = "@";

    private static final Duration POLL = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final @Nullable DataSource dataSource;
    private final @Nullable JdbcClient jdbc;
    private final String node = UUID.randomUUID().toString();
    private final Map<String, List<Runnable>> handlers = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> messageHandlers = new ConcurrentHashMap<>();
    private volatile @Nullable CacheManager local;
    private volatile boolean running;
    private volatile boolean listening;
    private @Nullable Thread listener;

    /** Without a data source (web slices) the caches stay local to the node. */
    public CacheInvalidations(@Nullable DataSource dataSource, @Nullable JdbcClient jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    /** The node's own caches, cleared on a notice from another node. */
    void attach(CacheManager caches) {
        this.local = caches;
    }

    /** Runs {@code action} after the current transaction commits, or now outside one. */
    static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * Runs {@code handler} on this node when another node publishes {@code name}, and after a lost connection. For a
     * node-local copy that is not a Spring cache, such as the search settings.
     */
    @Override
    public void onNotice(String name, Runnable handler) {
        handlers.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void onMessage(String topic, Consumer<String> handler) {
        messageHandlers
                .computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>())
                .add(handler);
    }

    /** Sends {@code message} on {@code topic} to the other nodes, by the same rule as {@link #publish}. */
    @Override
    public void send(String topic, String message) {
        if (topic.isEmpty() || topic.contains(" ")) {
            throw new IllegalArgumentException("A message topic is one word: " + topic);
        }
        notice(MESSAGE + topic + " " + message);
    }

    /**
     * Tells the other nodes that {@code name} (a cache or a notice with handlers) is stale once the change commits.
     * Inside a transaction of this data source the notice is sent on the transaction's own connection: PostgreSQL
     * delivers it at the commit and drops it with a rollback, and a notice that cannot be queued fails the change
     * rather than leaving the other nodes stale. Otherwise it goes out after the commit (or now) on a connection of
     * its own, never on one still bound to a finished transaction.
     */
    @Override
    public void publish(String name) {
        notice(name);
    }

    private void notice(String name) {
        if (jdbc == null || dataSource == null) {
            return;
        }
        String payload = node + " " + name;
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(dataSource)) {
            jdbc.sql("select pg_notify(:channel, :payload)")
                    .param("channel", CHANNEL)
                    .param("payload", payload)
                    .query()
                    .listOfRows();
            return;
        }
        afterCommit(() -> notifyOwnConnection(name, payload));
    }

    private void notifyOwnConnection(String name, String payload) {
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("select pg_notify(?, ?)")) {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            statement.setString(1, CHANNEL);
            statement.setString(2, payload);
            statement.execute();
        } catch (SQLException | RuntimeException failure) {
            // The change is committed; the other nodes catch up when the entry expires (10 minutes at most).
            log.warn("cache_invalidation_notify_failed cache={} error={}", name, failure.toString());
        }
    }

    /** Whether the listening connection is open: notices from other nodes reach this node. */
    public boolean isListening() {
        return listening;
    }

    @Override
    public void start() {
        if (dataSource == null || running) {
            return;
        }
        running = true;
        listener = Thread.ofVirtual().name("cache-invalidations").start(this::listen);
    }

    @Override
    public void stop() {
        running = false;
        Thread thread = listener;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listen() {
        Duration backoff = POLL;
        while (running) {
            try (Connection connection = dataSource().getConnection()) {
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("listen " + CHANNEL);
                }
                PGConnection postgres = connection.unwrap(PGConnection.class);
                listening = true;
                // Notices sent while this node did not listen are lost: whatever it cached may be stale.
                clear(ALL);
                backoff = POLL;
                while (running) {
                    PGNotification[] notices = postgres.getNotifications((int) POLL.toMillis());
                    if (notices != null) {
                        for (PGNotification notice : notices) {
                            apply(notice.getParameter());
                        }
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                listening = false;
                if (!running) {
                    return;
                }
                log.warn("cache_invalidation_listen_failed retryIn={} error={}", backoff, failure.toString());
                if (!sleep(backoff)) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
        listening = false;
    }

    private void apply(String payload) {
        int space = payload.indexOf(' ');
        if (space <= 0 || payload.substring(0, space).equals(node)) {
            return;
        }
        String body = payload.substring(space + 1);
        if (body.startsWith(MESSAGE)) {
            deliver(body.substring(MESSAGE.length()));
        } else {
            clear(body);
        }
    }

    /** Runs the handlers of a message's topic; {@code body} is the topic, a space and the message. */
    private void deliver(String body) {
        int space = body.indexOf(' ');
        if (space <= 0) {
            return;
        }
        String message = body.substring(space + 1);
        for (Consumer<String> handler : messageHandlers.getOrDefault(body.substring(0, space), List.of())) {
            try {
                handler.accept(message);
            } catch (RuntimeException failure) {
                // One failing handler must not keep the message from the others or stop the listener.
                log.warn("cluster_message_handler_failed error={}", failure.toString());
            }
        }
    }

    private void clear(String cacheName) {
        CacheManager caches = local;
        if (caches != null) {
            if (ALL.equals(cacheName)) {
                caches.getCacheNames().forEach(name -> clearOne(caches, name));
            } else {
                clearOne(caches, cacheName);
            }
        }
        if (ALL.equals(cacheName)) {
            handlers.values().forEach(CacheInvalidations::runAll);
        } else {
            runAll(handlers.getOrDefault(cacheName, List.of()));
        }
    }

    private static void runAll(List<Runnable> actions) {
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException failure) {
                // One failing handler must not keep the notice from the others or stop the listener.
                log.warn("cache_invalidation_handler_failed error={}", failure.toString());
            }
        }
    }

    private static void clearOne(CacheManager caches, String name) {
        Cache cache = caches.getCache(name);
        if (cache != null) {
            cache.clear();
        }
    }

    private DataSource dataSource() {
        if (dataSource == null) {
            throw new IllegalStateException("no data source");
        }
        return dataSource;
    }

    private static boolean sleep(Duration pause) {
        try {
            Thread.sleep(pause);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
