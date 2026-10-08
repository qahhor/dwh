package com.smartup24.cms.instance.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Meters of one delivery outbox (plan 10/10, item 7.3; docs/ops/slo.md): the delivery time by outcome, the dead
 * letters and the backlog its worker samples. The only label besides the outcome is the outbox name, a constant of
 * the owning module.
 *
 * <p>Prometheus names: {@code smc_outbox_delivery_seconds{outbox,outcome}}, {@code smc_outbox_dead_letters_total},
 * {@code smc_outbox_pending}, {@code smc_outbox_lag_seconds}. The backlog is the same table seen from every node: the
 * alert rules take its maximum over the instances.
 */
public final class OutboxMetrics {

    private final @Nullable MeterRegistry registry;
    private final String outbox;
    private final AtomicReference<Backlog> backlog = new AtomicReference<>(Backlog.EMPTY);

    private OutboxMetrics(@Nullable MeterRegistry registry, String outbox) {
        this.registry = registry;
        this.outbox = outbox;
        if (registry != null) {
            Gauge.builder("smc.outbox.pending", backlog, value -> value.get().pending())
                    .description("Outbox items due for delivery now")
                    .tag("outbox", outbox)
                    .register(registry);
            Gauge.builder("smc.outbox.lag", backlog, value -> value.get().lagSeconds())
                    .description("How long the oldest due outbox item has been waiting past its due time")
                    .baseUnit("seconds")
                    .tag("outbox", outbox)
                    .register(registry);
            Counter.builder("smc.outbox.dead.letters")
                    .description("Outbox items that ran out of attempts")
                    .tag("outbox", outbox)
                    .register(registry);
        }
    }

    /** Meters of the named outbox in the registry; {@code null} registry gives meters that record nothing. */
    public static OutboxMetrics of(@Nullable MeterRegistry registry, String outbox) {
        return new OutboxMetrics(registry, outbox);
    }

    /** Meters that record nothing, for workers built by hand. */
    public static OutboxMetrics none(String outbox) {
        return new OutboxMetrics(null, outbox);
    }

    /** One delivered item and the time the attempt took. */
    public void delivered(long elapsedNanos) {
        record("success", elapsedNanos);
    }

    /** One failed attempt: the item waits for a retry or, out of attempts, becomes a dead letter. */
    public void failed(long elapsedNanos, boolean deadLetter) {
        record(deadLetter ? "dead_letter" : "retry", elapsedNanos);
        if (deadLetter && registry != null) {
            registry.counter("smc.outbox.dead.letters", "outbox", outbox).increment();
        }
    }

    /** The latest backlog sample. */
    public void backlog(Backlog sample) {
        backlog.set(sample);
    }

    private void record(String outcome, long elapsedNanos) {
        if (registry == null) {
            return;
        }
        Timer.builder("smc.outbox.delivery")
                .description("Time of one outbox delivery attempt, by outcome")
                .tag("outbox", outbox)
                .tag("outcome", outcome)
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }
}
