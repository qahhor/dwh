package com.smartup24.cms.instance.config.jobs;

import com.smartup24.cms.instance.fnd.jobs.FndJobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driver of the shared job queue: every few seconds it enqueues scheduled jobs that are due and runs the queue,
 * the jobs of all modules (upload parsing, export, cleanup). It lives in the application wiring because the
 * {@code fnd} core does not schedule anything itself. Disabled in tests
 * ({@code smc.jobs.ticker-enabled=false}): tests call {@code runQueued()} themselves.
 */
@Component
@ConditionalOnProperty(name = "smc.jobs.ticker-enabled", matchIfMissing = true)
public class JobQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(JobQueueWorker.class);

    private final FndJobRunner runner;

    public JobQueueWorker(FndJobRunner runner) {
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${smc.jobs.tick:PT5S}")
    public void tick() {
        try {
            runner.enqueueDue();
            runner.runQueued();
        } catch (RuntimeException failure) {
            log.error("job_tick_failed", failure);
        }
    }
}
