package com.smartup24.cms.instance.jobs.runner;

import com.smartup24.cms.instance.jobs.repository.JobQueueRepository;
import com.smartup24.cms.instance.jobs.repository.JobQueueRepository.QueueState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Meters of the job queue (plan 10/10, item 7.3; docs/ops/slo.md): the run time of each attempt by handler and outcome
 * ({@code smc_jobs_execution_seconds{handler,outcome}}; the handler is a code a module registered, so the label stays
 * finite) and the queue state sampled every half minute ({@code smc_jobs_queue_due}, {@code smc_jobs_queue_lag_seconds},
 * {@code smc_jobs_queue_failed}). Every node sees the same queue: the alert rules take the maximum over the instances.
 */
@Component
public class JobMetrics {

    /** Meters that record nothing, for runners built by hand. */
    public static final JobMetrics NONE = new JobMetrics((MeterRegistry) null, null);

    private static final Logger log = LoggerFactory.getLogger(JobMetrics.class);

    private final @Nullable MeterRegistry registry;
    private final @Nullable JobQueueRepository queue;
    private final AtomicReference<QueueState> state = new AtomicReference<>(new QueueState(0, 0, 0));

    @Autowired
    public JobMetrics(ObjectProvider<MeterRegistry> meters, JobQueueRepository queue) {
        this(meters.getIfAvailable(), queue);
    }

    JobMetrics(@Nullable MeterRegistry registry, @Nullable JobQueueRepository queue) {
        this.registry = registry;
        this.queue = queue;
        if (registry != null) {
            Gauge.builder("smc.jobs.queue.due", state, value -> value.get().due())
                    .description("Jobs due now and not leased by any node")
                    .register(registry);
            Gauge.builder("smc.jobs.queue.lag", state, value -> value.get().lagSeconds())
                    .description("How long the oldest due job has been waiting past its due time")
                    .baseUnit("seconds")
                    .register(registry);
            Gauge.builder("smc.jobs.queue.failed", state, value -> value.get().failed())
                    .description("Jobs out of attempts, kept for the operator")
                    .register(registry);
        }
    }

    /** One attempt of a job, from the claim to the end of its handler. */
    public void executed(String handler, long elapsedNanos, boolean success) {
        if (registry == null) {
            return;
        }
        Timer.builder("smc.jobs.execution")
                .description("Run time of one job attempt, by handler and outcome")
                .tag("handler", handler)
                .tag("outcome", success ? "success" : "failure")
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    @Scheduled(fixedDelayString = "${smc.metrics.backlog-interval:PT30S}", initialDelayString = "PT10S")
    public void sample() {
        if (queue == null) {
            return;
        }
        try {
            state.set(queue.state());
        } catch (RuntimeException e) {
            log.warn("job_queue_sample_failed error={}", e.toString());
        }
    }
}
