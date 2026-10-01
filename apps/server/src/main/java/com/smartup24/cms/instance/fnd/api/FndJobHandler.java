package com.smartup24.cms.instance.fnd.api;

import java.util.Map;

/**
 * A handler for a foundation job. The schedule and the queue live in the {@code fnd_job_*} tables; the foundation
 * has no Spring scheduler: jobs are taken by the job runner, which a deployment step or an instance worker
 * calls.
 *
 * <p>The runner calls a handler with no transaction open (plan 10/10, item 3.8): a handler that needs atomicity opens
 * its own short transactions, and long work (reading a file, writing pg-dwh) holds none. A handler may run more than
 * once for one job — after a failure, or when its node died mid-run — so it checks the state it is about to change.
 */
public interface FndJobHandler {

    /** The handler code, as written in {@code fnd_job_schedule.handler}. */
    String code();

    /** Runs the job; an exception marks the run failed and its text is kept in {@code fnd_job_runs.error}. */
    void run(Map<String, Object> args);

    /**
     * Runs the job as the given attempt; the runner calls this one. A handler that closes its record on failure
     * overrides it: while {@link FndJobAttempt#last()} is false it rethrows a transient failure (see
     * {@link FndJobFailures#isTransient}) and leaves the record for the retry; a failure a retry would not fix it
     * reports with {@link FndJobNotRetryableException}.
     */
    default void run(Map<String, Object> args, FndJobAttempt attempt) {
        run(args);
    }
}
