package com.smartup24.cms.instance.config.cache;

import java.util.Collection;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * The node's caches, told to the rest of the cluster (plan 10/10, item 3.13, ADR-0025). Reads and puts stay local;
 * an eviction or a clear is applied at once, applied again after the commit (a request that re-read the old row
 * before the commit cannot keep it), and published to the other nodes, which clear the same cache.
 */
public class ClusterCacheManager implements CacheManager {

    private final CacheManager local;
    private final CacheInvalidations invalidations;

    public ClusterCacheManager(CacheManager local, CacheInvalidations invalidations) {
        this.local = local;
        this.invalidations = invalidations;
        invalidations.attach(local);
    }

    @Override
    public @Nullable Cache getCache(String name) {
        Cache cache = local.getCache(name);
        return cache == null ? null : new ClusterCache(cache, invalidations);
    }

    @Override
    public Collection<String> getCacheNames() {
        return local.getCacheNames();
    }

    private record ClusterCache(Cache delegate, CacheInvalidations invalidations) implements Cache {

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        public @Nullable ValueWrapper get(Object key) {
            return delegate.get(key);
        }

        @Override
        public <T> @Nullable T get(Object key, @Nullable Class<T> type) {
            return delegate.get(key, type);
        }

        @Override
        public <T> @Nullable T get(Object key, Callable<T> valueLoader) {
            return delegate.get(key, valueLoader);
        }

        @Override
        public void put(Object key, @Nullable Object value) {
            delegate.put(key, value);
        }

        @Override
        public void evict(Object key) {
            delegate.evict(key);
            stale();
        }

        @Override
        public boolean evictIfPresent(Object key) {
            boolean present = delegate.evictIfPresent(key);
            stale();
            return present;
        }

        @Override
        public void clear() {
            delegate.clear();
            stale();
        }

        @Override
        public boolean invalidate() {
            boolean present = delegate.invalidate();
            stale();
            return present;
        }

        /** The whole cache is cleared on the other nodes: a key need not survive a trip through a text notice. */
        private void stale() {
            CacheInvalidations.afterCommit(delegate::clear);
            invalidations.publish(delegate.getName());
        }
    }
}
