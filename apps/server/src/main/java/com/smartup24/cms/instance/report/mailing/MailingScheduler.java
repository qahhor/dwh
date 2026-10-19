package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.instance.common.schedule.CalendarSchedule;
import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.MailingRow;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tick of report mailings (ADR-0045), a scheduled job of the shared queue (every minute, one node at a time): the
 * active mailings whose next send is due are locked ({@code for update skip locked}), each gets the run of its latest
 * due slot — the unique (mailing, slot) row makes it once across nodes and retries — and its next send moves to the
 * first slot after now. After downtime a mailing sends once, for its latest missed slot, never a burst of the slots it
 * missed.
 */
@Component
public class MailingScheduler implements JobHandler {
    public static final String JOB = "report.mailing_tick";
    /** Mailings taken per tick; the rest stay due for the next one. */
    static final int BATCH = 100;

    private final ReportMailingRepository mailings;
    private final ReportMailingRunRepository runs;
    private final JobQueue jobs;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MailingScheduler(
            ReportMailingRepository mailings,
            ReportMailingRunRepository runs,
            @Lazy JobQueue jobs,
            PlatformTransactionManager transactions,
            ObjectProvider<Clock> clock) {
        this.mailings = mailings;
        this.runs = runs;
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

    /** Starts the runs due now; answers how many started. */
    public int tick() {
        Instant now = clock.instant();
        Integer started = transaction.execute(status -> {
            int count = 0;
            for (MailingRow mailing : mailings.lockDue(now, BATCH)) {
                CalendarSchedule schedule = MailingRequests.schedule(mailing);
                Optional<Long> run = runs.insertScheduled(mailing.id(), schedule.latestAtOrBefore(now));
                if (run.isPresent()) {
                    jobs.enqueueOnce(MailingRunner.JOB, Map.of("runId", run.get()));
                    count++;
                }
                mailings.moveNextRun(mailing.id(), schedule.nextAfter(now));
            }
            return count;
        });
        return started == null ? 0 : started;
    }
}
