package com.smartup24.cms.instance.config.jobs;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driver of the shared job queue: every few seconds it enqueues scheduled jobs that are due and runs the queue,
 * the jobs of all modules (upload parsing, export, cleanup). It lives in the application wiring because the
 * {@code fnd} core does not schedule anything itself. Disabled in tests
 * ({@code smc.jobs.ticker-enabled=false}): tests call {@code runQueued()} themselves.
 *
 * <p>The scheduler starts with the context, before the startup runners: on a fresh database the first tick would run
 * before {@code InstanceBootstrap} creates the first administrator and {@code MdSystemAccountBootstrap} the technical
 * account, and a job acting as {@code system} would fail (plan 10/10, item 6.5). So the ticks wait for
 * {@link ApplicationReadyEvent}, which follows every runner.
 */
@Component
@ConditionalOnProperty(name = "smc.jobs.ticker-enabled", matchIfMissing = true)
public class JobQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(JobQueueWorker.class);

    private final JobRunner runner;
    private volatile boolean ready;

    public JobQueueWorker(JobRunner runner) {
        this.runner = runner;
    }

    /** The startup runners are done: the instance and its technical account exist. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ready = true;
    }

    @Scheduled(fixedDelayString = "${smc.jobs.tick:PT5S}")
    public void tick() {
        if (!ready) {
            log.debug("job_tick_skipped_before_ready");
            return;
        }
        try {
            runner.enqueueDue();
            runner.runQueued();
        } catch (RuntimeException failure) {
            log.error("job_tick_failed", failure);
        }
    }
}
