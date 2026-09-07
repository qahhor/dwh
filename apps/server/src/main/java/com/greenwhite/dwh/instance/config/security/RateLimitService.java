package com.greenwhite.dwh.instance.config.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.TokensInheritanceStrategy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory bucket'ы Bucket4j по ключу (ip:/user:/api:). Достаточно для одного
 * инстанса приложения на экземпляр (ТЗ-01: несколько нод — отдельное решение).
 * Ограничение роста карты: при превышении порога вычищаются записи,
 * к которым не обращались дольше 10 минут.
 */
@Service
public class RateLimitService {

    private static final int CLEANUP_THRESHOLD = 50_000;
    private static final long STALE_AFTER_MS = Duration.ofMinutes(10).toMillis();

    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
    private final TimeMeter timeMeter;

    public RateLimitService() {
        this(TimeMeter.SYSTEM_NANOTIME);
    }

    RateLimitService(TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
    }

    /** Пытается списать 1 токен; возвращает probe с остатком и временем до пополнения. */
    public ConsumptionProbe tryConsume(String key, int limitPerMinute) {
        return tryConsume(key, limitPerMinute, limitPerMinute);
    }

    public ConsumptionProbe tryConsume(String key, int limitPerMinute, int capacity) {
        Budget budget = new Budget(limitPerMinute, capacity);
        Entry entry = buckets.computeIfAbsent(key, k -> new Entry(newBucket(budget), budget));
        entry.lastAccessMs.set(System.currentTimeMillis());
        maybeCleanup();
        synchronized (entry) {
            if (!budget.equals(entry.budget)) {
                entry.bucket.replaceConfiguration(configuration(budget), TokensInheritanceStrategy.AS_IS);
                entry.budget = budget;
            }
            return entry.bucket.tryConsumeAndReturnRemaining(1);
        }
    }

    /**
     * Анти-флуд для security-журнала: true не чаще раза в минуту на ключ —
     * иначе атака превращала бы журнал во вторую жертву.
     */
    public boolean shouldLogRejection(String key) {
        Entry entry = buckets.get(key);
        if (entry == null) {
            return true;
        }
        long nowMin = System.currentTimeMillis() / 60_000;
        long prev = entry.lastLoggedMinute.get();
        return prev != nowMin && entry.lastLoggedMinute.compareAndSet(prev, nowMin);
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
        return Bandwidth.classic(budget.capacity,
                Refill.greedy(budget.perMinute, Duration.ofMinutes(1)));
    }

    private void maybeCleanup() {
        if (buckets.size() <= CLEANUP_THRESHOLD) {
            return;
        }
        long staleBefore = System.currentTimeMillis() - STALE_AFTER_MS;
        buckets.entrySet().removeIf(e -> e.getValue().lastAccessMs.get() < staleBefore);
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
