package com.smartup24.cms.instance.config.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/** Plan 10/10, item 3.13: the node-local side of the cluster cache. */
class ClusterCacheManagerTest {

    private final CaffeineCacheManager local = new CaffeineCacheManager();
    private final ClusterCacheManager caches;

    ClusterCacheManagerTest() {
        local.setCacheNames(List.of("probe"));
        caches = new ClusterCacheManager(local, new CacheInvalidations(null, null));
    }

    @Test
    @DisplayName("3.13: reads and puts stay local; every way of dropping an entry clears it")
    void readsPutsAndDrops() {
        Cache cache = caches.getCache("probe");
        assertThat(caches.getCache("unknown")).isNull();
        assertThat(caches.getCacheNames()).containsExactly("probe");
        assertThat(cache.getName()).isEqualTo("probe");
        assertThat(cache.getNativeCache()).isNotNull();

        assertThat(cache.get("a", () -> "loaded")).isEqualTo("loaded");
        assertThat(cache.get("a", String.class)).isEqualTo("loaded");
        assertThat(cache.evictIfPresent("a")).isTrue();
        assertThat(cache.get("a")).isNull();

        cache.put("b", "x");
        cache.evict("b");
        assertThat(cache.get("b")).isNull();

        cache.put("c", "y");
        assertThat(cache.invalidate()).isTrue();
        assertThat(cache.get("c")).isNull();
    }

    @Test
    @DisplayName("3.13: without a data source the caches stay local and nothing listens")
    void withoutDataSource() {
        CacheInvalidations invalidations = new CacheInvalidations(null, null);
        invalidations.start();

        assertThat(invalidations.isRunning()).isFalse();
        assertThat(invalidations.isListening()).isFalse();
    }

    @Test
    @DisplayName("3.13: a node that cannot reach the database keeps trying and stops when asked")
    void unreachableDatabaseIsRetried() throws Exception {
        DataSource down = mock(DataSource.class);
        when(down.getConnection()).thenThrow(new SQLException("database is down"));
        CacheInvalidations invalidations = new CacheInvalidations(down, null);

        invalidations.start();
        Thread.sleep(200);
        assertThat(invalidations.isRunning()).isTrue();
        assertThat(invalidations.isListening()).isFalse();

        invalidations.stop();
        assertThat(invalidations.isRunning()).isFalse();
    }
}
