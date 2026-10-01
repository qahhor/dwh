package com.smartup24.cms.instance.jobs.runner;

import com.smartup24.cms.instance.jobs.repository.JobQueueRepository;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The heartbeat of one claimed job (plan 10/10, item 3.8): a virtual thread renews the lease every third of it while
 * the handler works. A renewal that fails (the database away for a moment) is logged and the next one is tried:
 * giving up would let the lease run out while the handler still works and another node run the same job. Only a
 * renewal that finds the claim gone — another node took the job after the lease ran out — stops the heartbeat and
 * marks the lease lost, so the runner does not record this attempt's outcome over the other node's.
 */
final class JobLease implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobLease.class);
    /** How long the end of a job waits for a renewal in flight. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(10);

    private final JobQueueRepository queue;
    private final Duration lease;
    private final long queueId;
    private final String handler;
    private final String token;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final CountDownLatch stop = new CountDownLatch(1);
    private final Thread heartbeat;

    private JobLease(JobQueueRepository queue, Duration lease, long queueId, String handler, String token) {
        this.queue = queue;
        this.lease = lease;
        this.queueId = queueId;
        this.handler = handler;
        this.token = token;
        this.heartbeat = Thread.ofVirtual().name("job-lease-" + queueId).unstarted(this::renew);
    }

    /** Starts renewing the lease the claim {@code token} holds on the queue row. */
    static JobLease start(JobQueueRepository queue, Duration lease, long queueId, String handler, String token) {
        JobLease started = new JobLease(queue, lease, queueId, handler, token);
        started.heartbeat.start();
        return started;
    }

    /** True once a renewal found the claim gone: another node holds the job now. */
    boolean lost() {
        return lost.get();
    }

    /** Stops the heartbeat and lets a renewal in flight end, so a lease lost at the very end is known. */
    @Override
    public void close() {
        stop.countDown();
        try {
            heartbeat.join(STOP_WAIT);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            log.warn("job_heartbeat_wait_interrupted queue_id={}", queueId, stopped);
        }
    }

    private void renew() {
        long pauseMs = Math.max(1, lease.toMillis() / 3);
        try {
            while (!stop.await(pauseMs, TimeUnit.MILLISECONDS)) {
                if (renewOnce() == 0) {
                    lost.set(true);
                    log.warn("job_lease_lost handler={} queue_id={}", handler, queueId);
                    return;
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            log.debug("job_heartbeat_interrupted queue_id={}", queueId, stopped);
        }
    }

    /** One renewal: the rows renewed (0 — the claim is gone), or -1 when the database did not answer. */
    private int renewOnce() {
        try {
            return queue.renewLease(queueId, token, lease.toMillis());
        } catch (RuntimeException renewalFailure) {
            log.warn("job_lease_renewal_failed handler={} queue_id={}", handler, queueId, renewalFailure);
            return -1;
        }
    }
}
