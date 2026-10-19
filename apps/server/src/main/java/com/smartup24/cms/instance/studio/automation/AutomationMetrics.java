package com.smartup24.cms.instance.studio.automation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The meters of automations (ADR-0046): {@code smc_automation_runs_total} by outcome ({@code done}, {@code skipped},
 * {@code failed}, {@code retried}), {@code smc_automation_run_seconds} — from the triggering change to the end of the
 * run — {@code smc_automation_events_withheld_total} by reason ({@code self}, {@code depth}, {@code record_rate},
 * {@code installation_rate}) and {@code smc_automation_queue_oldest_seconds}, the age of the oldest queued run, which
 * the scheduler samples every minute. The alerts {@code SmartupcmsAutomationsFailing} and
 * {@code SmartupcmsAutomationQueueStuck} watch them (RB-15).
 */
@Component
public class AutomationMetrics {
    private final @Nullable MeterRegistry registry;
    private final AtomicLong oldest = new AtomicLong();

    public AutomationMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
        if (this.registry != null) {
            Gauge.builder("smc.automation.queue.oldest", oldest, AtomicLong::get)
                    .baseUnit("seconds")
                    .description("Age of the oldest queued automation run")
                    .register(this.registry);
        }
    }

    public void run(String outcome, @Nullable Duration latency) {
        if (registry == null) return;
        registry.counter("smc.automation.runs", "outcome", outcome).increment();
        if (latency != null && !latency.isNegative()) {
            Timer.builder("smc.automation.run")
                    .description("From the triggering change to the end of an automation run")
                    .register(registry)
                    .record(latency);
        }
    }

    public void withheld(String reason) {
        if (registry != null)
            registry.counter("smc.automation.events.withheld", "reason", reason).increment();
    }

    /** The age of the oldest queued run, zero when none waits. */
    public void oldestQueued(Duration age) {
        oldest.set(Math.max(0, age.toSeconds()));
    }
}
