package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.schedule.CalendarSchedule;
import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository.RecordKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tick of automations (ADR-0046), a scheduled job of the shared queue (every minute, one node at a time): the due
 * scheduled automations are locked ({@code for update skip locked}), each gets the look at its records for its latest
 * due slot and its next look moves to the first slot after now — after downtime once, for the latest missed slot; the
 * records whose queued runs are due again (a retry, a job lost with its node) get a job that takes them; the age of the
 * oldest queued run is sampled for {@code smc_automation_queue_oldest_seconds}.
 */
@Component
public class AutomationScheduler implements JobHandler {
    public static final String JOB = "studio.automation_tick";
    static final int BATCH = 50;
    private static final Duration STALE = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

    private final StudioAutomationRepository automations;
    private final StudioAutomationRunRepository runs;
    private final AutomationJson json;
    private final AutomationMetrics metrics;
    private final JobQueue jobs;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AutomationScheduler(
            StudioAutomationRepository automations,
            StudioAutomationRunRepository runs,
            AutomationJson json,
            AutomationMetrics metrics,
            @Lazy JobQueue jobs,
            PlatformTransactionManager transactions,
            ObjectProvider<Clock> clock) {
        this.automations = automations;
        this.runs = runs;
        this.json = json;
        this.metrics = metrics;
        this.jobs = jobs;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public String code() {
        return JOB;
    }

    @Override
    public void run(Map<String, Object> args) {
        tick();
    }

    /** Starts what is due now; answers how many looks and record jobs started. */
    public int tick() {
        Instant now = clock.instant();
        Integer started = transaction.execute(status -> {
            int count = 0;
            for (AutomationRow automation : automations.lockDue(now, BATCH)) {
                CalendarSchedule schedule = schedule(automation);
                if (schedule == null) continue;
                jobs.enqueueOnce(
                        AutomationScans.JOB,
                        Map.of(
                                "automationId",
                                automation.id(),
                                "slot",
                                Long.toString(schedule.latestAtOrBefore(now).getEpochSecond())));
                automations.moveNextRun(automation.id(), schedule.nextAfter(now));
                count++;
            }
            for (RecordKey record : runs.dueRecords(now, now.minus(STALE), BATCH)) {
                jobs.enqueueOnce(AutomationRunner.JOB, Map.of("form", record.form(), "recordId", record.recordId()));
                count++;
            }
            return count;
        });
        metrics.oldestQueued(
                runs.oldestQueued().map(oldest -> Duration.between(oldest, now)).orElse(Duration.ZERO));
        return started == null ? 0 : started;
    }

    /** The schedule of a stored automation, or null when it no longer reads. */
    private @org.jspecify.annotations.Nullable CalendarSchedule schedule(AutomationRow automation) {
        try {
            return AutomationChecks.scheduleOf(json.trigger(automation.trigger()), automation.timeZone());
        } catch (RuntimeException unreadable) {
            log.warn("studio_automation_schedule_unreadable automation={}", automation.id(), unreadable);
            return null;
        }
    }
}
