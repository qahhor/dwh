package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository.RunRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes the queued runs of one record (ADR-0046), a job of the shared queue: one run at a time in the order they were
 * queued, each in its own transaction that holds the record's runs — its actions and its outcome commit together, so a
 * run that committed never runs again and one that did not leaves nothing behind. A failure a retry may fix (a stale
 * revision, the database) waits {@code 30 s}, then {@code 2 min}, and fails for good after {@value #MAX_ATTEMPTS}
 * attempts; a refusal fails at once. While a run waits for its retry, the later runs of the record wait behind it; the
 * scheduler takes them again when it is due.
 */
@Component
public class AutomationRunner implements JobHandler {
    public static final String JOB = "studio.automation_drain";
    static final int MAX_ATTEMPTS = 3;
    /** The runs one job takes at most; the scheduler takes the rest. */
    static final int MAX_PER_JOB = 100;

    private static final Logger log = LoggerFactory.getLogger(AutomationRunner.class);

    private final StudioAutomationRunRepository runs;
    private final StudioAutomationRepository automations;
    private final AutomationForms forms;
    private final AutomationChecks checks;
    private final AutomationJson json;
    private final AutomationExecutor executor;
    private final AutomationMetrics metrics;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AutomationRunner(
            StudioAutomationRunRepository runs,
            StudioAutomationRepository automations,
            AutomationForms forms,
            AutomationChecks checks,
            AutomationJson json,
            AutomationExecutor executor,
            AutomationMetrics metrics,
            PlatformTransactionManager transactions,
            ObjectProvider<Clock> clock) {
        this.runs = runs;
        this.automations = automations;
        this.forms = forms;
        this.checks = checks;
        this.json = json;
        this.executor = executor;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public String code() {
        return JOB;
    }

    @Override
    public void run(Map<String, Object> args) {
        drain(String.valueOf(args.get("form")), Long.parseLong(String.valueOf(args.get("recordId"))));
    }

    /** Takes the due runs of the record in order; answers how many ended. */
    public int drain(String form, long recordId) {
        int ended = 0;
        for (int taken = 0; taken < MAX_PER_JOB; taken++) {
            AtomicReference<RunRow> current = new AtomicReference<>();
            AtomicReference<AutomationExecutor.Outcome> refused = new AtomicReference<>();
            try {
                Boolean more = transaction.execute(status -> {
                    boolean ran = next(form, recordId, current, refused);
                    if (refused.get() != null) status.setRollbackOnly();
                    return ran;
                });
                if (!Boolean.TRUE.equals(more)) return ended;
            } catch (RuntimeException failure) {
                RunRow run = current.get();
                if (run == null) throw failure;
                log.warn("studio_automation_run_error run={} automation={}", run.id(), run.automationId(), failure);
                refused.set(
                        AutomationExecutor.Outcome.failed("internal", AutomationExecutor.internal(), List.of(), true));
            }
            AutomationExecutor.Outcome outcome = refused.get();
            if (outcome != null) record(java.util.Objects.requireNonNull(current.get()), outcome);
            ended++;
        }
        return ended;
    }

    /**
     * Runs the first queued run of the record, when it is due; answers whether one ran. An outcome that needs the
     * transaction rolled back is handed over in {@code refused} and recorded after.
     */
    private boolean next(
            String form,
            long recordId,
            AtomicReference<RunRow> current,
            AtomicReference<AutomationExecutor.Outcome> refused) {
        runs.lockRecord(form, recordId);
        Optional<RunRow> next = runs.nextQueued(form, recordId);
        if (next.isEmpty() || !next.get().due()) return false;
        RunRow run = next.get();
        current.set(run);
        AutomationExecutor.Outcome outcome = execute(run);
        if (outcome.rollback()) {
            refused.set(outcome);
            return true;
        }
        runs.finish(
                run.id(),
                outcome.status(),
                outcome.reason(),
                outcome.error() == null ? null : json.write(outcome.error()),
                json.write(outcome.results()));
        ended(run, outcome.status(), outcome.reason());
        return true;
    }

    private AutomationExecutor.Outcome execute(RunRow run) {
        Optional<AutomationRow> found = automations.find(run.automationId());
        if (found.isEmpty()) return AutomationExecutor.Outcome.skipped("deleted");
        AutomationRow automation = found.get();
        if (!automation.active()) return AutomationExecutor.Outcome.skipped("paused");
        AutomationSpec spec;
        try {
            spec = checks.check(automation.form(), json.request(automation), AutomationChecks.Context.run());
        } catch (ApiException outdated) {
            return AutomationExecutor.Outcome.failed(
                    "definition", AutomationExecutor.problem(outdated), List.of(), false);
        }
        Optional<AutomationForm> form = forms.find(spec.form());
        if (form.isEmpty()) return AutomationExecutor.Outcome.skipped("form_gone");
        Map<String, Object> payload = json.object(run.payload());
        Map<String, Object> old = payload.get("old") instanceof Map<?, ?> map ? strings(map) : Map.of();
        Set<String> changed = payload.get("changed") instanceof List<?> list
                ? Set.copyOf(list.stream().map(String::valueOf).toList())
                : Set.of();
        boolean gone = AutomationSpec.DELETED.equals(run.triggerKind());
        AutomationRun current = new AutomationRun(
                automation.id(),
                automation.name(),
                automation.authorId(),
                "run-" + run.id(),
                form.get(),
                run.recordId(),
                run.depth(),
                false,
                spec.zone(),
                clock.instant(),
                old,
                changed);
        if (gone) current.readGone(old);
        return executor.perform(spec, current, gone);
    }

    /**
     * Records the outcome of an attempt whose transaction rolled back: a retry later for a failure another attempt may
     * fix, else the end of the run — failed, or skipped when the record went meanwhile.
     */
    private void record(RunRow run, AutomationExecutor.Outcome outcome) {
        String error = outcome.error() == null ? null : json.write(outcome.error());
        boolean again = outcome.transientFailure() && run.attempts() + 1 < MAX_ATTEMPTS;
        transaction.executeWithoutResult(status -> {
            if (again) {
                runs.retryAt(run.id(), clock.instant().plus(backoff(run.attempts() + 1)), outcome.reason(), error);
            } else {
                runs.finish(run.id(), outcome.status(), outcome.reason(), error, json.write(outcome.results()));
            }
        });
        if (again) {
            metrics.run("retried", null);
            log.info(
                    "studio_automation_run_retry run={} automation={} reason={}",
                    run.id(),
                    run.automationId(),
                    outcome.reason());
        } else {
            ended(run, outcome.status(), outcome.reason());
        }
    }

    /** The pause after the given failed attempt: 30 seconds, then 2 minutes. */
    static Duration backoff(int attempt) {
        return attempt <= 1 ? Duration.ofSeconds(30) : Duration.ofMinutes(2);
    }

    private void ended(RunRow run, String status, @org.jspecify.annotations.Nullable String reason) {
        Instant now = clock.instant();
        metrics.run(status, Duration.between(run.createdAt(), now));
        log.info(
                "studio_automation_run automation={} run={} record={} status={} reason={}",
                run.automationId(),
                run.id(),
                run.recordId(),
                status,
                reason);
    }

    private static Map<String, Object> strings(Map<?, ?> map) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> values.put(String.valueOf(key), value));
        return values;
    }
}
