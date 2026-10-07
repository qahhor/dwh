package com.smartup24.cms.instance.jobs.runner;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.jobs.api.JobAttempt;
import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.jobs.api.JobNotRetryableException;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.jobs.config.JobProperties;
import com.smartup24.cms.instance.jobs.repository.JobQueueRepository;
import com.smartup24.cms.instance.jobs.repository.JobQueueRepository.ClaimedRow;
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
 * Takes jobs off the {@code fnd_job_queue} queue and records each outcome in {@code fnd_job_runs}. There is
 * deliberately no scheduler here: the instance chooses when to run, and the "enqueue, then run" sequence is the same
 * in production and in tests.
 *
 * <p>Plan 10/10, item 3.8: a job is leased, not held in a transaction. A short transaction takes one due row
 * ({@code for update skip locked}), stamps the claim and the end of the lease on it and records the run; the handler
 * then works with no transaction of the runner open (parsing a 50 MB file used to keep one open for minutes), and
 * another short transaction records the outcome. While the handler works, {@link JobLease} renews the lease; a
 * node that dies stops renewing, and once the lease runs out another runner takes the job again. The outcome of an
 * attempt is recorded only while its claim still holds the job. A failed attempt is
 * retried after a doubling pause until {@link JobProperties#maxAttempts()}, then the row is marked failed and
 * stays for the operator. Handlers open their own transactions where they need atomicity.
 *
 * <p>Scheduling takes a transaction-scoped advisory lock, so two nodes that tick together enqueue a due scheduled
 * job once. A runner takes only the jobs whose handler it knows: during a rolling upgrade an old node leaves a new
 * kind of job to the new one instead of failing it. The switch {@code jobs_enabled=false} in the instance settings
 * ({@link JobSwitch}) stops the runner from taking jobs. The SQL lives in {@link JobQueueRepository} (plan 10/10,
 * item 4.2).
 */
@Component
public class JobRunner implements JobQueue {

    /** The switch key in the instance settings; with no such setting, jobs run. */
    public static final String JOBS_ENABLED_KEY = JobSwitch.KEY;

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    /** This process, as the claims name it: an operator reads which node holds a job. */
    private static final String NODE = ManagementFactory.getRuntimeMXBean().getName();

    private final JobQueueRepository queue;
    /** The arguments column of the queue (plan 10/10, item 3.11). */
    private final JsonColumns columns;

    private final TransactionTemplate tx;
    private final JobProperties settings;
    private final JobSwitch jobSwitch;
    private final JobMetrics metrics;
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final String[] handlerCodes;

    @Autowired
    public JobRunner(
            JobQueueRepository queue,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            List<JobHandler> handlers,
            JobProperties settings,
            JobSwitch jobSwitch,
            JobMetrics metrics) {
        this.queue = queue;
        this.metrics = metrics;
        this.columns = new JsonColumns(json, "fnd_job_queue");
        this.tx = new TransactionTemplate(transactions);
        this.settings = settings;
        this.jobSwitch = jobSwitch;
        handlers.forEach(handler -> this.handlers.put(handler.code(), handler));
        this.handlerCodes = this.handlers.keySet().toArray(String[]::new);
    }

    /** A runner built by hand with the given switch, for tests of the switch itself. */
    public JobRunner(
            JdbcClient jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            List<JobHandler> handlers,
            JobProperties settings,
            JobSwitch jobSwitch) {
        this(new JobQueueRepository(jdbc), json, transactions, handlers, settings, jobSwitch, JobMetrics.NONE);
    }

    /** A runner built by hand with the given retry and lease; it is never switched off. */
    public JobRunner(
            JdbcClient jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            List<JobHandler> handlers,
            JobProperties settings) {
        this(jdbc, json, transactions, handlers, settings, JobSwitch.ON);
    }

    /** A runner with the default retry and lease, for tests and tools that build one by hand. */
    public JobRunner(
            JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transactions, List<JobHandler> handlers) {
        this(jdbc, json, transactions, handlers, JobProperties.defaults());
    }

    /**
     * Enqueues the scheduled jobs that are due. Two nodes ticking together: the one that gets
     * the advisory lock enqueues, the other returns 0 at once; the conditional update would stop a duplicate even
     * without the lock, which only spares the second node the wait on the schedule rows.
     */
    public int enqueueDue() {
        Integer queued = tx.execute(status -> queue.tryEnqueueLock() ? queue.enqueueDue(handlerCodes) : 0);
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
        if (!jobSwitch.jobsEnabled()) {
            return null;
        }
        String token = NODE + "/" + UUID.randomUUID();
        Optional<ClaimedRow> claimed = queue.claimNext(handlerCodes, token, millis(settings.lease()));
        if (claimed.isEmpty()) {
            return null;
        }
        ClaimedRow row = claimed.get();
        // A run still 'running' belongs to an attempt whose node died: its lease ran out
        queue.expireRunningRuns(row.id());
        if (row.attempt() > settings.maxAttempts()) {
            queue.markFailed(row.id(), token);
            log.error(
                    "job_failed_for_good handler={} queue_id={} attempts={}",
                    row.handler(),
                    row.id(),
                    row.attempt() - 1);
            return new Claim(row.id(), row.handler(), row.args(), row.attempt(), token, null);
        }
        long runId = queue.insertRun(row.id(), row.handler(), row.args(), row.attempt());
        return new Claim(row.id(), row.handler(), row.args(), row.attempt(), token, runId);
    }

    private boolean execute(Claim claim) {
        RuntimeException failure = null;
        long started = System.nanoTime();
        JobLease lease = JobLease.start(queue, settings.lease(), claim.queueId(), claim.handler(), claim.token());
        try {
            handler(claim.handler())
                    .run(args(claim.rawArgs()), new JobAttempt(claim.attempt(), settings.maxAttempts()));
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            lease.close();
            metrics.executed(claim.handler(), System.nanoTime() - started, failure == null);
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
            queue.delete(claim.queueId(), claim.token());
            return;
        }
        if (claim.attempt() >= settings.maxAttempts() || failure instanceof JobNotRetryableException) {
            queue.markFailed(claim.queueId(), claim.token());
            return;
        }
        queue.scheduleRetry(claim.queueId(), claim.token(), millis(settings.backoffAfter(claim.attempt())));
    }

    /**
     * Closes the run of this attempt, on the condition that the claim still holds the job and the run is still
     * {@code running}: a node that took the job over after the lease ran out has already closed it as
     * {@code lease expired}, and that stays.
     *
     * @return 1 when recorded, 0 when the claim no longer holds the job
     */
    private int recordRun(Claim claim, RuntimeException failure) {
        return queue.closeRun(
                claim.runId(),
                claim.queueId(),
                claim.token(),
                failure == null ? "done" : "failed",
                failure == null ? null : describe(failure));
    }

    private static long millis(Duration duration) {
        return duration.toMillis();
    }

    private JobHandler handler(String code) {
        JobHandler handler = handlers.get(code);
        if (handler == null) {
            throw new IllegalStateException("Обработчик " + code + " не зарегистрирован");
        }
        return handler;
    }

    /** Enqueues a scheduled job outside its schedule, e.g. from a deployment step or an on-demand check. */
    @Transactional
    @Override
    public void enqueue(String scheduleCode) {
        if (queue.enqueueScheduled(scheduleCode) == 0) {
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
        queue.enqueueOnce(handlerCode, columns.object(args));
    }

    /** Error text for {@code fnd_job_runs.error}: the exception and its cause chain, keeping an SQLException's text. */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.toString());
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            text.append(" <- ").append(cause);
        }
        return text.toString();
    }

    private Map<String, Object> args(String rawArgs) {
        return columns.readObject(rawArgs);
    }
}
