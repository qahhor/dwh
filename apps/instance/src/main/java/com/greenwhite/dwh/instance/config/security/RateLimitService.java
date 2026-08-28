package com.greenwhite.dwh.instance.config.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

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
@Component
public class RateLimitService {

    private static final int CLEANUP_THRESHOLD = 50_000;
    private static final long STALE_AFTER_MS = Duration.ofMinutes(10).toMillis();

    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();

    /** Пытается списать 1 токен; возвращает probe с остатком и временем до пополнения. */
    public ConsumptionProbe tryConsume(String key, int limitPerMinute) {
        Entry entry = buckets.computeIfAbsent(key, k -> new Entry(newBucket(limitPerMinute)));
        entry.lastAccessMs.set(System.currentTimeMillis());
        maybeCleanup();
        return entry.bucket.tryConsumeAndReturnRemaining(1);
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

    private static Bucket newBucket(int limitPerMinute) {
        Bandwidth limit = Bandwidth.classic(limitPerMinute,
                Refill.greedy(limitPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private void maybeCleanup() {
        if (buckets.size() <= CLEANUP_THRESHOLD) {
            return;
        }
        long staleBefore = System.currentTimeMillis() - STALE_AFTER_MS;
        buckets.entrySet().removeIf(e -> e.getValue().lastAccessMs.get() < staleBefore);
    }

    private record Entry(Bucket bucket, AtomicLong lastAccessMs, AtomicLong lastLoggedMinute) {
        Entry(Bucket bucket) {
            this(bucket, new AtomicLong(System.currentTimeMillis()), new AtomicLong(-1));
        }
    }
}
