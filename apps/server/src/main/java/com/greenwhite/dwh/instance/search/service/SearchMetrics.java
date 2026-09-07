package com.greenwhite.dwh.instance.search.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/** Only finite, server-owned enums enter labels. No query, collection, actor or job identifier is accepted. */
@Component
public class SearchMetrics {
    private final MeterRegistry registry;
    private final Map<String,AtomicLong> gaugeValues=new ConcurrentHashMap<>();
    public SearchMetrics(MeterRegistry registry) { this.registry=registry; }
    public static SearchMetrics unmetered() { return new SearchMetrics(null); }
    public void query(String entity,String source,boolean error,boolean fallback,long elapsedNanos) {
        if (registry==null) return;
        String safeEntity=finite(entity,Set.of("ALL","TASK","PROJECT","USER"));
        String safeSource=finite(source,Set.of("POSTGRES","TYPESENSE"));
        registry.timer("dwh.search.query.duration","entity",safeEntity,"source",safeSource,"outcome",error ? "ERROR" : "SUCCESS")
                .record(Math.max(0,elapsedNanos),TimeUnit.NANOSECONDS);
        if (error) registry.counter("dwh.search.query.errors","entity",safeEntity).increment();
        if (fallback) registry.counter("dwh.search.fallback","entity",safeEntity).increment();
    }
    public void engine(String entity,long milliseconds) {
        if (registry!=null) registry.timer("dwh.search.engine.duration","entity",finite(entity,Set.of("TASK","PROJECT","USER")))
                .record(Math.max(0,milliseconds),TimeUnit.MILLISECONDS);
    }
    public void rejected() { if (registry!=null) registry.counter("dwh.search.rate.rejections").increment(); }
    public void imported(boolean success,long rows) {
        if (registry!=null && rows>0) registry.counter("dwh.search.import.rows","outcome",success ? "SUCCESS" : "FAILURE").increment(rows);
    }
    public void retry() { if (registry!=null) registry.counter("dwh.search.delivery.retries").increment(); }
    public void queue(boolean active,long pending,long lagSeconds) {
        gauge("dwh.search.delivery.pending",active ? "ACTIVE" : "CANDIDATE",pending);
        gauge("dwh.search.delivery.lag.seconds",active ? "ACTIVE" : "CANDIDATE",lagSeconds);
    }
    public void job(String action,String state,Duration duration) {
        if (registry==null) return;
        String safeAction=finite(action,Set.of("CHECK","REBUILD","ROLLBACK"));
        String safeState=finite(state,Set.of("QUEUED","RUNNING","VERIFYING","ACTIVATING","SUCCEEDED","FAILED","CANCELLED"));
        registry.counter("dwh.search.job.states","action",safeAction,"state",safeState).increment();
        if (duration!=null) registry.timer("dwh.search.job.duration","action",safeAction,"state",safeState).record(duration.isNegative() ? Duration.ZERO : duration);
    }
    public void switched(String action) {
        if (registry!=null) registry.counter("dwh.search.generation.switches","action",finite(action,Set.of("REBUILD","ROLLBACK"))).increment();
    }
    private void gauge(String name,String role,long value) {
        if (registry==null) return;
        gaugeValues.computeIfAbsent(name+role,key -> {
            var holder=new AtomicLong();Gauge.builder(name,holder,AtomicLong::get).tag("role",role).register(registry);return holder;
        }).set(Math.max(0,value));
    }
    private static String finite(String value,Set<String> allowed) { return value!=null && allowed.contains(value) ? value : "UNKNOWN"; }
}
