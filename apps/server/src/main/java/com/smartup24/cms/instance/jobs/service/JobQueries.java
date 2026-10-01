package com.smartup24.cms.instance.jobs.service;

import com.smartup24.cms.instance.jobs.repository.JobQueueRepository;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Read-only questions other modules ask about the job queue, so they do not read {@code fnd_job_queue} themselves
 * (plan 10/10, item 1.3). A separate bean from {@code JobRunner}: a job handler may ask them, and the runner is
 * built from the handlers.
 */
@Component
public class JobQueries {

    private final JobQueueRepository queue;

    public JobQueries(JobQueueRepository queue) {
        this.queue = queue;
    }

    /**
     * The values of one argument over the jobs of a handler that are still to run: queued, waiting for a retry or
     * leased — every job not failed for good (plan 10/10, item 3.8). A module asks this before it treats a record as
     * abandoned by its job.
     */
    public Set<String> pendingArgumentValues(String handler, String argument) {
        return queue.pendingArgumentValues(handler, argument);
    }
}
