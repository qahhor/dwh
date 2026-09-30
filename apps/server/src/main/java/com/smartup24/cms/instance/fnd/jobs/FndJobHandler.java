package com.smartup24.cms.instance.fnd.jobs;

import java.util.Map;

/**
 * A handler for a foundation job. The schedule and the queue live in the {@code fnd_job_*} tables; the foundation
 * has no Spring scheduler: jobs are taken by {@link FndJobRunner}, which a deployment step or an instance worker
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
}
