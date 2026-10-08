package com.smartup24.cms.instance.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The run time and outcome of a nightly maintenance task (plan 10/10, item 7.3; docs/ops/slo.md):
 * {@code smc_task_run_seconds{task,outcome}}. The task name is a constant of the owning module.
 */
public final class TaskRunMetrics {

    private TaskRunMetrics() {}

    /** Records one run that started at {@code startedNanos} ({@link System#nanoTime()}). */
    public static void record(@Nullable MeterRegistry registry, String task, long startedNanos, boolean success) {
        if (registry == null) {
            return;
        }
        Timer.builder("smc.task.run")
                .description("Run time of a nightly maintenance task, by outcome")
                .tag("task", task)
                .tag("outcome", success ? "success" : "failure")
                .register(registry)
                .record(Math.max(0, System.nanoTime() - startedNanos), TimeUnit.NANOSECONDS);
    }
}
