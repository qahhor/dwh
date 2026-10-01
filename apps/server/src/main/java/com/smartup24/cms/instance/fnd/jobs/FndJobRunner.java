package com.smartup24.cms.instance.fnd.jobs;

import com.smartup24.cms.instance.fnd.api.FndJobAttempt;
import com.smartup24.cms.instance.fnd.api.FndJobHandler;
import com.smartup24.cms.instance.fnd.api.FndJobNotRetryableException;
import com.smartup24.cms.instance.fnd.api.FndJobQueue;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Takes foundation jobs off the {@code fnd_job_queue} queue and records each outcome in {@code fnd_job_runs}.
 * There is deliberately no scheduler here: the instance chooses when to run, and the "enqueue, then run" sequence is
 * the same in production and in tests.
 *
 * <p>Plan 10/10, item 3.8: a job is leased, not held in a transaction. A short transaction takes one due row
 * ({@code for update skip locked}), stamps the claim and the end of the lease on it and records the run; the handler
 * then works with no transaction of the runner open (parsing a 50 MB file used to keep one open for minutes), and
 * another short transaction records the outcome. While the handler works, {@link FndJobLease} renews the lease; a
 * node that dies stops renewing, and once the lease runs out another runner takes the job again. The outcome of an
 * attempt is recorded only while its claim still holds the job. A failed attempt is
 * retried after a doubling pause until {@link FndJobProperties#maxAttempts()}, then the row is marked failed and
 * stays for the operator. Handlers open their own transactions where they need atomicity.
 *
 * <p>Scheduling takes a transaction-scoped advisory lock, so two nodes that tick together enqueue a due scheduled
 * job once. A runner takes only the jobs whose handler it knows: during a rolling upgrade an old node leaves a new
 * kind of job to the new one instead of failing it. The switch {@code jobs_enabled=false} in the framework's
 * {@code md_settings} (the row with {@code user_id is null}) stops the runner from taking jobs.
 */
@Component
public class FndJobRunner implements FndJobQueue {

    /** The switch key in the framework's {@code md_settings}; with no such row, jobs run. */
    public static final String JOBS_ENABLED_KEY = "jobs_enabled";

    private static final Logger log = LoggerFactory.getLogger(FndJobRunner.class);
    /** This process, as the claims name it: an operator reads which node holds a job. */
    private static final String NODE = ManagementFactory.getRuntimeMXBean().getName();

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final FndJobProperties settings;
    private final Map<String, FndJobHandler> handlers = new HashMap<>();
    private final String[] handlerCodes;

    @Autowired
    public FndJobRunner(
            JdbcClient jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            List<FndJobHandler> handlers,
            FndJobProperties settings) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.settings = settings;
        handlers.forEach(handler -> this.handlers.put(handler.code(), handler));
        this.handlerCodes = this.handlers.keySet().toArray(String[]::new);
    }

    /** A runner with the default retry and lease, for tests and tools that build one by hand. */
    public FndJobRunner(
            JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transactions, List<FndJobHandler> handlers) {
        this(jdbc, json, transactions, handlers, FndJobProperties.defaults());
    }

    /**
     * Enqueues the scheduled jobs that are due. Two nodes ticking together: the one that gets
     * the advisory lock enqueues, the other returns 0 at once; the conditional update would stop a duplicate even
     * without the lock, which only spares the second node the wait on the schedule rows.
     */
    public int enqueueDue() {
        Integer queued = tx.execute(status -> {
            boolean mine = jdbc.sql("select pg_try_advisory_xact_lock(hashtext('fnd.jobs.enqueue_due'))")
                    .query(Boolean.class)
                    .single();
            if (!mine) {
                return 0;
            }
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
                            """).param("handlers", handlerCodes).update();
        });
        return queued == null ? 0 : queued;
    }

    /**
     * Runs due jobs one by one until the queue is empty. A successful job leaves the queue; a failed one waits for
     * its next attempt or, once out of attempts, stays marked with {@code failed_at}. Every attempt is kept in
     * {@code fnd_job_runs}: successful ones as {@code done}, failed ones as {@code failed} with the error text and
     * {@code args}.
     *
     * @return the number of jobs that succeeded
     */
    public int runQueued() {
        int done = 0;
        while (true) {
            Optional<Boolean> outcome = runNext();
            if (outcome.isEmpty()) {
                return done;
            }
            if (outcome.get()) {
                done++;
            }
        }
    }

    /**
     * Takes one job and runs it outside the queue transaction.
     *
     * @return empty when the queue is empty or jobs are switched off; otherwise {@code true} on success and
     *     {@code false} on failure
     */
    public Optional<Boolean> runNext() {
        Claim claim = tx.execute(status -> claim());
        if (claim == null) {
            return Optional.empty();
        }
        if (claim.runId() == null) {
            // Taken again after its lease ran out, with no attempt left: the node died on the last one
            return Optional.of(false);
        }
        return Optional.of(execute(claim));
    }

    /** One claimed job: the row, the claim that holds it, its attempt and the run recorded for it. */
    private record Claim(long queueId, String handler, String rawArgs, int attempt, String token, Long runId) {}

    private Claim claim() {
        if (!jobsEnabled()) {
            return null;
        }
        String token = NODE + "/" + UUID.randomUUID();
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
                .param("handlers", handlerCodes)
                .param("token", token)
                .param("lease", millis(settings.lease()))
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        long queueId = ((Number) row.get("id")).longValue();
        String handlerCode = (String) row.get("handler");
        String rawArgs = (String) row.get("args");
        int attempt = ((Number) row.get("attempts")).intValue();
        // A run still 'running' belongs to an attempt whose node died: its lease ran out
        jdbc.sql("update fnd_job_runs set status = 'failed', finished_at = now(), error = 'lease expired'"
                        + " where queue_id = :id and status = 'running'")
                .param("id", queueId)
                .update();
        if (attempt > settings.maxAttempts()) {
            markFailed(queueId, token);
            log.error("job_failed_for_good handler={} queue_id={} attempts={}", handlerCode, queueId, attempt - 1);
            return new Claim(queueId, handlerCode, rawArgs, attempt, token, null);
        }
        long runId = jdbc.sql("insert into fnd_job_runs (queue_id, handler, args, status, attempt)"
                        + " values (:queue, :handler, cast(:args as jsonb), 'running', :attempt) returning id")
                .param("queue", queueId)
                .param("handler", handlerCode)
                .param("args", rawArgs)
                .param("attempt", attempt)
                .query(Long.class)
                .single();
        return new Claim(queueId, handlerCode, rawArgs, attempt, token, runId);
    }

    private boolean execute(Claim claim) {
        RuntimeException failure = null;
        FndJobLease lease = FndJobLease.start(jdbc, settings.lease(), claim.queueId(), claim.handler(), claim.token());
        try {
            handler(claim.handler())
                    .run(args(claim.rawArgs()), new FndJobAttempt(claim.attempt(), settings.maxAttempts()));
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            lease.close();
        }
        RuntimeException outcome = failure;
        tx.executeWithoutResult(status -> finish(claim, outcome, lease.lost()));
        return failure == null;
    }

    /** Records the outcome, only while this claim still holds the job: a node that took it over records its own. */
    private void finish(Claim claim, RuntimeException failure, boolean leaseLost) {
        if (failure != null) {
            log.error(
                    "job_failed handler={} queue_id={} attempt={}",
                    claim.handler(),
                    claim.queueId(),
                    claim.attempt(),
                    failure);
        }
        if (leaseLost || recordRun(claim, failure) == 0) {
            log.warn(
                    "job_outcome_not_recorded_lease_lost handler={} queue_id={} attempt={} failed={}",
                    claim.handler(),
                    claim.queueId(),
                    claim.attempt(),
                    failure != null);
            return;
        }
        if (failure == null) {
            jdbc.sql("delete from fnd_job_queue where id = :id and locked_by = :token")
                    .param("id", claim.queueId())
                    .param("token", claim.token())
                    .update();
            return;
        }
        if (claim.attempt() >= settings.maxAttempts() || failure instanceof FndJobNotRetryableException) {
            markFailed(claim.queueId(), claim.token());
            return;
        }
        jdbc.sql("""
                        update fnd_job_queue
                           set next_run_at = now() + :pause * interval '1 millisecond', locked_by = null, locked_until = null
                         where id = :id and locked_by = :token
                        """)
                .param("pause", millis(settings.backoffAfter(claim.attempt())))
                .param("id", claim.queueId())
                .param("token", claim.token())
                .update();
    }

    /**
     * Closes the run of this attempt, on the condition that the claim still holds the job and the run is still
     * {@code running}: a node that took the job over after the lease ran out has already closed it as
     * {@code lease expired}, and that stays.
     *
     * @return 1 when recorded, 0 when the claim no longer holds the job
     */
    private int recordRun(Claim claim, RuntimeException failure) {
        return jdbc.sql("""
                        update fnd_job_runs r
                           set status = :status, finished_at = now(), error = :error
                         where r.id = :run and r.status = 'running'
                           and exists (select 1 from fnd_job_queue q where q.id = :queue and q.locked_by = :token)
                        """)
                .param("status", failure == null ? "done" : "failed")
                .param("error", failure == null ? null : describe(failure))
                .param("run", claim.runId())
                .param("queue", claim.queueId())
                .param("token", claim.token())
                .update();
    }

    private void markFailed(long queueId, String token) {
        jdbc.sql("update fnd_job_queue set failed_at = now(), locked_by = null, locked_until = null"
                        + " where id = :id and locked_by = :token")
                .param("id", queueId)
                .param("token", token)
                .update();
    }

    private static long millis(Duration duration) {
        return duration.toMillis();
    }

    private FndJobHandler handler(String code) {
        FndJobHandler handler = handlers.get(code);
        if (handler == null) {
            throw new IllegalStateException("Обработчик " + code + " не зарегистрирован");
        }
        return handler;
    }

    private boolean jobsEnabled() {
        return jdbc.sql("select value from md_settings where user_id is null and key = :key")
                .param("key", JOBS_ENABLED_KEY)
                .query(String.class)
                .optional()
                .map(value -> !"false".equalsIgnoreCase(value.trim()))
                .orElse(true);
    }

    /** Enqueues a scheduled job outside its schedule, e.g. from a deployment step or an on-demand check. */
    @Transactional
    @Override
    public void enqueue(String scheduleCode) {
        int queued = jdbc.sql("""
                        insert into fnd_job_queue (handler, args, schedule_code)
                        select handler, args, code from fnd_job_schedule where code = :code
                        """).param("code", scheduleCode).update();
        if (queued == 0) {
            throw new IllegalArgumentException("Задание " + scheduleCode + " отсутствует в расписании");
        }
    }

    /**
     * Enqueues a one-off job with arguments, outside any schedule. It joins the caller's transaction, so the business
     * module's record and the job appear together or not at all.
     */
    @Transactional
    @Override
    public void enqueueOnce(String handlerCode, Map<String, Object> args) {
        handler(handlerCode);
        jdbc.sql("insert into fnd_job_queue (handler, args) values (:handler, cast(:args as jsonb))")
                .param("handler", handlerCode)
                .param("args", json.writeValueAsString(args))
                .update();
    }

    /** Error text for {@code fnd_job_runs.error}: the exception and its cause chain, keeping an SQLException's text. */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.toString());
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            text.append(" <- ").append(cause);
        }
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> args(String rawArgs) {
        if (rawArgs == null) {
            return Map.of();
        }
        return json.readValue(rawArgs, Map.class);
    }
}
