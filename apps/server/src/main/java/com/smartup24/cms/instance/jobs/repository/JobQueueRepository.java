package com.smartup24.cms.instance.jobs.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The tables of the job queue: the schedule {@code fnd_job_schedule}, the queue {@code fnd_job_queue} and the runs
 * {@code fnd_job_runs} (plan 10/10, item 4.2; the table names stay, ADR-0020). Every write that closes an attempt is
 * conditioned on the claim token, so an attempt whose lease ran out never records over the node that took the job
 * over (plan 10/10, item 3.8).
 */
@Repository
public class JobQueueRepository {

    private final JdbcClient jdbc;

    public JobQueueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A job row just claimed: its id, handler, arguments as JSON text and the attempt it now runs. */
    public record ClaimedRow(long id, String handler, String args, int attempt) {}

    /**
     * The queue as the metrics see it (plan 10/10, item 7.3): jobs due and not leased, the wait of the oldest of them
     * past its due time in seconds, and the jobs out of attempts that wait for the operator.
     */
    public record QueueState(long due, double lagSeconds, long failed) {}

    public QueueState state() {
        return jdbc.sql("""
                        select count(*) filter (where ready) as due,
                               coalesce(extract(epoch from now() - min(next_run_at) filter (where ready)), 0) as lag,
                               count(*) filter (where failed_at is not null) as failed
                          from (select next_run_at, failed_at,
                                       failed_at is null and next_run_at <= now()
                                           and (locked_until is null or locked_until < now()) as ready
                                  from fnd_job_queue) q
                        """)
                .query((rs, row) -> new QueueState(rs.getLong("due"), rs.getDouble("lag"), rs.getLong("failed")))
                .single();
    }

    /**
     * The transaction-scoped lock that lets one node enqueue the due scheduled jobs; false when another node holds
     * it.
     */
    public boolean tryEnqueueLock() {
        return jdbc.sql("select pg_try_advisory_xact_lock(hashtext('fnd.jobs.enqueue_due'))")
                .query(Boolean.class)
                .single();
    }

    /** Enqueues the scheduled jobs of the given handlers that are due and stamps their schedule rows. */
    public int enqueueDue(String[] handlers) {
        return jdbc.sql("""
                        with due as (
                            update fnd_job_schedule set last_enqueued = now()
                             where enabled
                               and handler = any (cast(:handlers as text[]))
                               and (last_enqueued is null
                                    or last_enqueued + make_interval(secs => interval_sec) <= now())
                            returning code, handler, args)
                        insert into fnd_job_queue (handler, args, schedule_code)
                        select handler, args, code from due
                        """).param("handlers", handlers).update();
    }

    /**
     * Claims the next due job of the given handlers ({@code for update skip locked}): stamps the claim token, the end
     * of the lease and the next attempt on it.
     */
    public Optional<ClaimedRow> claimNext(String[] handlers, String token, long leaseMillis) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        with next as (
                            select id from fnd_job_queue
                             where failed_at is null
                               and next_run_at <= now()
                               and (locked_until is null or locked_until < now())
                               and handler = any (cast(:handlers as text[]))
                             order by next_run_at, id
                               for update skip locked
                             limit 1)
                        update fnd_job_queue q
                           set locked_by = :token,
                               locked_until = now() + :lease * interval '1 millisecond',
                               attempts = q.attempts + 1
                          from next
                         where q.id = next.id
                        returning q.id, q.handler, q.args::text as args, q.attempts
                        """)
                .param("handlers", handlers)
                .param("token", token)
                .param("lease", leaseMillis)
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new ClaimedRow(
                ((Number) row.get("id")).longValue(),
                (String) row.get("handler"),
                (String) row.get("args"),
                ((Number) row.get("attempts")).intValue()));
    }

    /** Closes a run still {@code running} for the job: its attempt's node died and the lease ran out. */
    public void expireRunningRuns(long queueId) {
        jdbc.sql("update fnd_job_runs set status = 'failed', finished_at = now(), error = 'lease expired'"
                        + " where queue_id = :id and status = 'running'")
                .param("id", queueId)
                .update();
    }

    /** Records the run of one attempt as {@code running}; returns its id. */
    public long insertRun(long queueId, String handler, String rawArgs, int attempt) {
        return jdbc.sql("insert into fnd_job_runs (queue_id, handler, args, status, attempt)"
                        + " values (:queue, :handler, cast(:args as jsonb), 'running', :attempt) returning id")
                .param("queue", queueId)
                .param("handler", handler)
                .param("args", rawArgs)
                .param("attempt", attempt)
                .query(Long.class)
                .single();
    }

    /**
     * Closes the run of an attempt, on the condition that the claim still holds the job and the run is still
     * {@code running}.
     *
     * @return 1 when recorded, 0 when the claim no longer holds the job
     */
    public int closeRun(long runId, long queueId, String token, String status, String error) {
        return jdbc.sql("""
                        update fnd_job_runs r
                           set status = :status, finished_at = now(), error = :error
                         where r.id = :run and r.status = 'running'
                           and exists (select 1 from fnd_job_queue q where q.id = :queue and q.locked_by = :token)
                        """)
                .param("status", status)
                .param("error", error)
                .param("run", runId)
                .param("queue", queueId)
                .param("token", token)
                .update();
    }

    /** Removes a job that succeeded, while the claim still holds it. */
    public void delete(long queueId, String token) {
        jdbc.sql("delete from fnd_job_queue where id = :id and locked_by = :token")
                .param("id", queueId)
                .param("token", token)
                .update();
    }

    /** Releases a failed job for its next attempt after the pause, while the claim still holds it. */
    public void scheduleRetry(long queueId, String token, long pauseMillis) {
        jdbc.sql("""
                        update fnd_job_queue
                           set next_run_at = now() + :pause * interval '1 millisecond', locked_by = null, locked_until = null
                         where id = :id and locked_by = :token
                        """)
                .param("pause", pauseMillis)
                .param("id", queueId)
                .param("token", token)
                .update();
    }

    /** Marks a job failed for good, while the claim still holds it; the row stays for the operator. */
    public void markFailed(long queueId, String token) {
        jdbc.sql("update fnd_job_queue set failed_at = now(), locked_by = null, locked_until = null"
                        + " where id = :id and locked_by = :token")
                .param("id", queueId)
                .param("token", token)
                .update();
    }

    /** Enqueues a scheduled job outside its schedule; returns 0 when the schedule has no such code. */
    public int enqueueScheduled(String scheduleCode) {
        return jdbc.sql("""
                        insert into fnd_job_queue (handler, args, schedule_code)
                        select handler, args, code from fnd_job_schedule where code = :code
                        """).param("code", scheduleCode).update();
    }

    /** Enqueues a one-off job with its arguments as JSON text. */
    public void enqueueOnce(String handler, String argsJson) {
        jdbc.sql("insert into fnd_job_queue (handler, args) values (:handler, cast(:args as jsonb))")
                .param("handler", handler)
                .param("args", argsJson)
                .update();
    }

    /** Renews the lease the claim holds; returns the rows renewed, 0 when the claim is gone. */
    public int renewLease(long queueId, String token, long leaseMillis) {
        return jdbc.sql("update fnd_job_queue set locked_until = now() + :lease * interval '1 millisecond'"
                        + " where id = :id and locked_by = :token")
                .param("lease", leaseMillis)
                .param("id", queueId)
                .param("token", token)
                .update();
    }

    /**
     * The values of one argument over the jobs of a handler that are still to run: every job not failed for good.
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
