package com.smartup24.cms.instance.config.jobs;

import com.smartup24.cms.instance.jobs.api.JobQueueDriver;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Samples the job queue gauges every half minute (plan 10/10, item 7.3; docs/ops/slo.md). It lives in the application
 * wiring for the same reason as {@link JobQueueWorker}: the {@code jobs} core schedules nothing itself (AC-7). It runs
 * on every node, whether or not the node runs jobs, so a stopped ticker still shows a growing lag.
 */
@Component
@Profile("!migrate")
public class JobQueueMetricsSampler {

    private final JobQueueDriver queue;

    public JobQueueMetricsSampler(JobQueueDriver queue) {
        this.queue = queue;
    }

    @Scheduled(fixedDelayString = "${smc.metrics.backlog-interval:PT30S}", initialDelayString = "PT10S")
    public void sample() {
        queue.sampleGauges();
    }
}
