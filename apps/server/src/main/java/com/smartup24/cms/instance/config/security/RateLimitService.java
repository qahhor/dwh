package com.smartup24.cms.instance.config.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.TokensInheritanceStrategy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * In-memory Bucket4j buckets per key (ip:/user:/api:). Enough for one
 * application process per instance (several nodes would need a separate design).
 * Map growth is bounded (FR-SEC-2): storage is a Caffeine cache
 * with a hard capacity limit (at most maxEntries, 10_000 by default) and
 * automatic W-TinyLFU eviction / expireAfterAccess(10 minutes).
 */
@Service
public class RateLimitService {

    public static final int DEFAULT_MAX_ENTRIES = 10_000;
    private static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    private final Cache<String, Entry> buckets;
    private final TimeMeter timeMeter;

    public RateLimitService() {
        this(TimeMeter.SYSTEM_NANOTIME, DEFAULT_MAX_ENTRIES);
    }

    @Autowired
    public RateLimitService(RateLimitProperties props) {
        this(TimeMeter.SYSTEM_NANOTIME, props != null ? props.maxEntries() : DEFAULT_MAX_ENTRIES);
    }

    RateLimitService(TimeMeter timeMeter) {
        this(timeMeter, DEFAULT_MAX_ENTRIES);
    }

    RateLimitService(TimeMeter timeMeter, int maxEntries) {
        this.timeMeter = timeMeter;
        int capacity = Math.max(100, maxEntries);
        this.buckets = Caffeine.newBuilder()
                .maximumSize(capacity)
                .expireAfterAccess(DEFAULT_EXPIRE_AFTER_ACCESS)
                .build();
    }

    /** Tries to consume 1 token; returns a probe with the remainder and the time until refill. */
    public ConsumptionProbe tryConsume(String key, int limitPerMinute) {
        return tryConsume(key, limitPerMinute, limitPerMinute);
    }

    public ConsumptionProbe tryConsume(String key, int limitPerMinute, int capacity) {
        Budget budget = new Budget(limitPerMinute, capacity);
        Entry entry = buckets.get(key, k -> new Entry(newBucket(budget), budget));
        entry.lastAccessMs.set(System.currentTimeMillis());
        synchronized (entry) {
            if (!budget.equals(entry.budget)) {
                entry.bucket.replaceConfiguration(configuration(budget), TokensInheritanceStrategy.AS_IS);
                entry.budget = budget;
            }
            return entry.bucket.tryConsumeAndReturnRemaining(1);
        }
    }

    /**
     * Flood protection for the security log: true at most once a minute per key;
     * otherwise an attack would make the log its second victim.
     */
    public boolean shouldLogRejection(String key) {
        Entry entry = buckets.getIfPresent(key);
        if (entry == null) {
            return true;
        }
        long nowMin = System.currentTimeMillis() / 60_000;
        long prev = entry.lastLoggedMinute.get();
        return prev != nowMin && entry.lastLoggedMinute.compareAndSet(prev, nowMin);
    }

    /** The current estimated number of buckets in memory. */
    public long estimatedSize() {
        return buckets.estimatedSize();
    }

    /** Synchronous cleanup for tests. */
    public void cleanUp() {
        buckets.cleanUp();
    }

    private Bucket newBucket(Budget budget) {
        return Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(bandwidth(budget))
                .build();
    }

    private static BucketConfiguration configuration(Budget budget) {
        return BucketConfiguration.builder().addLimit(bandwidth(budget)).build();
    }

    private static Bandwidth bandwidth(Budget budget) {
        return Bandwidth.classic(budget.capacity, Refill.greedy(budget.perMinute, Duration.ofMinutes(1)));
    }

    private static final class Entry {
        private final Bucket bucket;
        private final AtomicLong lastAccessMs = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong lastLoggedMinute = new AtomicLong(-1);
        private Budget budget;

        private Entry(Bucket bucket, Budget budget) {
            this.bucket = bucket;
            this.budget = budget;
        }
    }

    private record Budget(int perMinute, int capacity) {
        private Budget {
            if (perMinute < 1 || capacity < 1) {
                throw new IllegalArgumentException("Rate and capacity must be positive");
            }
        }
    }
}
