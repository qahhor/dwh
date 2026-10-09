package com.smartup24.cms.instance.jobs.api;

/**
 * What the application wiring drives: the jobs core schedules nothing itself, so the instance ticks the queue and
 * samples its gauges ({@code config.jobs}). The wiring sees this contract, not the runner (plan 10/10, item 1.3).
 */
public interface JobQueueDriver {

    /** Enqueues the scheduled jobs that are due; returns how many were queued. */
    int enqueueDue();

    /** Runs due jobs one by one until the queue is empty; returns how many succeeded. */
    int runQueued();

    /** Reads the queue state into the gauges of the queue (plan 10/10, item 7.3). */
    void sampleGauges();
}
