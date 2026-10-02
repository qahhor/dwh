package com.smartup24.cms.instance.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.event.EntityChanged;
import com.smartup24.cms.instance.common.entity.event.EntityEventType;
import com.smartup24.cms.instance.ms.task.service.MsTaskStatusEntity;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 10/10, item 3.13, acceptance: a change made on one node clears the cache of another within two seconds of its
 * commit, and a rolled-back change clears nothing. The second node is a cache manager of its own with its own
 * listener on the same database, as a second instance of the application would be.
 */
class ClusterCacheIntegrationTest extends EmbeddedPostgresTest {

    private static final Duration WITHIN = Duration.ofSeconds(2);

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CacheInvalidations invalidations;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    private CacheInvalidations secondNode;
    private CacheManager secondCaches;

    @BeforeEach
    void startSecondNode() {
        CaffeineCacheManager local = new CaffeineCacheManager();
        local.setCacheNames(cacheManager.getCacheNames());
        secondNode = new CacheInvalidations(dataSource, jdbc);
        secondCaches = new ClusterCacheManager(local, secondNode);
        secondNode.start();
        awaitTrue(() -> secondNode.isListening() && invalidations.isListening(), Duration.ofSeconds(10));
    }

    @AfterEach
    void stopSecondNode() {
        secondNode.stop();
    }

    @Test
    @DisplayName("3.13: a committed change on one node clears the cache of the other within two seconds")
    void committedChangeReachesTheOtherNode() {
        Cache remote = secondCaches.getCache(CacheConfig.NAVIGATION_ITEMS_CACHE);
        remote.put("all", "stale types");

        long started = System.nanoTime();
        transactions.executeWithoutResult(status ->
                cacheManager.getCache(CacheConfig.NAVIGATION_ITEMS_CACHE).clear());

        awaitTrue(() -> remote.get("all") == null, WITHIN);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(WITHIN);
    }

    @Test
    @DisplayName("3.13: a change of a reference entity clears the other node's cached items after its commit")
    void referenceChangeReachesTheOtherNode() {
        Long status = jdbc.sql("select id from ms_task_statuses order by id limit 1")
                .query(Long.class)
                .single();
        Cache remote = secondCaches.getCache(EntityEnums.CACHE);
        remote.put(MsTaskStatusEntity.CODE, "stale statuses");

        // What the runtime publishes in the transaction of a change of a status (ADR-0032, 6.9).
        transactions.executeWithoutResult(transaction -> events.publishEvent(new EntityChanged(
                MsTaskStatusEntity.CODE,
                MsTaskStatusEntity.DEFINITION.form(),
                status,
                2,
                EntityEventType.UPDATED,
                null,
                List.of("color"),
                null,
                Instant.now(),
                UUID.randomUUID())));

        awaitTrue(() -> remote.get(MsTaskStatusEntity.CODE) == null, WITHIN);
    }

    @Test
    @DisplayName("3.13: a rolled-back change tells no other node")
    void rolledBackChangeTellsNobody() throws InterruptedException {
        Cache remote = secondCaches.getCache(CacheConfig.CUSTOM_FIELDS_CACHE);
        remote.put("all", "still valid");

        transactions.executeWithoutResult(status -> {
            cacheManager.getCache(CacheConfig.CUSTOM_FIELDS_CACHE).clear();
            status.setRollbackOnly();
        });

        Thread.sleep(WITHIN.toMillis());
        assertThat(remote.get("all", String.class)).isEqualTo("still valid");
    }

    @Test
    @DisplayName("3.13: the notice sent inside a transaction reaches the other node only when it commits")
    void noticeWaitsForTheCommit() throws InterruptedException {
        Cache remote = secondCaches.getCache(CacheConfig.MODULE_ACTIVE_CACHE);
        remote.put("all", "stale until the commit");

        transactions.executeWithoutResult(status -> {
            cacheManager.getCache(CacheConfig.MODULE_ACTIVE_CACHE).clear();
            sleep(Duration.ofMillis(500));
            assertThat(remote.get("all", String.class)).isEqualTo("stale until the commit");
        });

        awaitTrue(() -> remote.get("all") == null, WITHIN);
    }

    @Test
    @DisplayName("3.13: a notice with handlers runs them on the other node, not on the sender")
    void noticeRunsHandlers() {
        AtomicInteger remoteRuns = new AtomicInteger();
        AtomicInteger ownRuns = new AtomicInteger();
        String notice = "probeNotice" + System.nanoTime();
        secondNode.onNotice(notice, remoteRuns::incrementAndGet);
        invalidations.onNotice(notice, ownRuns::incrementAndGet);

        transactions.executeWithoutResult(status -> invalidations.publish(notice));

        awaitTrue(() -> remoteRuns.get() == 1, WITHIN);
        assertThat(ownRuns).hasValue(0);
    }

    @Test
    @DisplayName("3.13: a node ignores its own notices and does not echo a remote one")
    void noEcho() {
        Cache own = secondCaches.getCache(CacheConfig.NAVIGATION_ITEMS_CACHE);
        Cache first = cacheManager.getCache(CacheConfig.NAVIGATION_ITEMS_CACHE);

        own.clear();
        own.put("all", "fresh on the second node");
        awaitTrue(() -> first.get("all") == null, WITHIN);
        first.put("all", "fresh on the first node");

        assertThat(own.get("all", String.class)).isEqualTo("fresh on the second node");
        assertThat(first.get("all", String.class)).isEqualTo("fresh on the first node");
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            sleep(Duration.ofMillis(20));
        }
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        }
    }
}
