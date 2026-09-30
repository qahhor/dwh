package com.smartup24.cms.instance.fnd.service;

import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read-only questions other modules ask about the job queue, so they do not read {@code fnd_job_queue} themselves
 * (plan 10/10, item 1.3). A separate bean from {@code FndJobRunner}: a job handler may ask them, and the runner is
 * built from the handlers.
 */
@Component
public class FndJobQueries {

    private final JdbcClient jdbc;

    public FndJobQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The values of one argument over the jobs of a handler that are still to run: queued, waiting for a retry or
     * leased — every job not failed for good (plan 10/10, item 3.8). A module asks this before it treats a record as
     * abandoned by its job.
     */
    public Set<String> pendingArgumentValues(String handler, String argument) {
        return new TreeSet<>(jdbc.sql("""
                        select distinct args ->> :argument
                          from fnd_job_queue
                         where handler = :handler and failed_at is null and args ->> :argument is not null
                        """)
                .param("argument", argument)
                .param("handler", handler)
                .query(String.class)
                .list());
    }
}
