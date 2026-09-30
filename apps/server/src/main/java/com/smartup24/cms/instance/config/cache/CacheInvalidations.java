package com.smartup24.cms.instance.config.cache;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
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
 * sends {@code NOTIFY smc_cache} once its transaction commits — a rolled-back change tells nobody — and every other
 * node, listening on a connection of its own, clears the same cache. After a lost connection the node clears all its
 * caches, since it may have missed a notice, and listens again.
 */
public class CacheInvalidations implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidations.class);

    static final String CHANNEL = "smc_cache";
    static final String ALL = "*";
    private static final Duration POLL = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final @Nullable DataSource dataSource;
    private final @Nullable JdbcClient jdbc;
    private final String node = UUID.randomUUID().toString();
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

    /** Tells the other nodes, once the change commits, that {@code cacheName} is stale. */
    void publish(String cacheName) {
        if (jdbc == null) {
            return;
        }
        afterCommit(() -> {
            try {
                jdbc.sql("select pg_notify(:channel, :payload)")
                        .param("channel", CHANNEL)
                        .param("payload", node + " " + cacheName)
                        .query()
                        .listOfRows();
            } catch (RuntimeException failure) {
                // The change is committed; the other nodes catch up when the entry expires (10 minutes at most).
                log.warn("cache_invalidation_notify_failed cache={} error={}", cacheName, failure.toString());
            }
        });
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
        clear(payload.substring(space + 1));
    }

    private void clear(String cacheName) {
        CacheManager caches = local;
        if (caches == null) {
            return;
        }
        if (ALL.equals(cacheName)) {
            caches.getCacheNames().forEach(name -> clearOne(caches, name));
        } else {
            clearOne(caches, cacheName);
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
