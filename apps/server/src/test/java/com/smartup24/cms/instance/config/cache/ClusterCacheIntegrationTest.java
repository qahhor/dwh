package com.smartup24.cms.instance.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.ms.task.service.MsTaskStatusService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.time.Duration;
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
    private MsTaskStatusService statuses;

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
        Cache remote = secondCaches.getCache(CacheConfig.TASK_TYPES_CACHE);
        remote.put("all", "stale types");

        long started = System.nanoTime();
        transactions.executeWithoutResult(
                status -> cacheManager.getCache(CacheConfig.TASK_TYPES_CACHE).clear());

        awaitTrue(() -> remote.get("all") == null, WITHIN);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(WITHIN);
    }

    @Test
    @DisplayName("3.13: a service change (@CacheEvict) clears the other node's cache after its commit")
    void serviceChangeReachesTheOtherNode() {
        Long status = jdbc.sql("select id from ms_task_statuses order by id limit 1")
                .query(Long.class)
                .single();
        long revision = jdbc.sql("select revision from ms_task_statuses where id = :id")
                .param("id", status)
                .query(Long.class)
                .single();
        Cache remote = secondCaches.getCache(CacheConfig.TASK_STATUSES_CACHE);
        remote.put("all", "stale statuses");

        statuses.updateStatusRecord(status, null, "#112233", null, null, revision);

        awaitTrue(() -> remote.get("all") == null, WITHIN);
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
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", interrupted);
            }
        }
    }
}
