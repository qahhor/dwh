package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.MailingRow;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.RecipientRow;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository.RunRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one run of a mailing (ADR-0045), a job of the shared queue: each recipient in turn, read anew from the mailing
 * (a test send goes to the person who asked). A recipient already recorded by an earlier attempt is not sent again,
 * so a retried job finishes the run without a second letter. A scheduled run of a mailing paused meanwhile is skipped.
 * The run's outcome: {@code done} when nothing failed, {@code failed} when every attempted delivery failed,
 * {@code partial} in between; skipped deliveries (the condition, no address) are not failures.
 */
@Component
public class MailingRunner implements JobHandler {
    public static final String JOB = "report.mailing_run";
    private static final Logger log = LoggerFactory.getLogger(MailingRunner.class);

    private final ReportMailingRepository mailings;
    private final ReportMailingRunRepository runs;
    private final MailingDispatcher dispatcher;
    private final MailingMetrics metrics;
    private final TransactionTemplate transaction;

    public MailingRunner(
            ReportMailingRepository mailings,
            ReportMailingRunRepository runs,
            MailingDispatcher dispatcher,
            MailingMetrics metrics,
            PlatformTransactionManager transactions) {
        this.mailings = mailings;
        this.runs = runs;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public String code() {
        return JOB;
    }

    @Override
    public void run(Map<String, Object> args) {
        run(Long.parseLong(String.valueOf(args.get("runId"))));
    }

    public void run(long runId) {
        Optional<RunRow> claimed = transaction.execute(status -> runs.claim(runId));
        if (claimed == null || claimed.isEmpty()) return;
        RunRow run = claimed.get();
        Optional<MailingRow> found = mailings.find(run.mailingId());
        if (found.isEmpty()) return;
        MailingRow mailing = found.get();
        if ("scheduled".equals(run.kind()) && !mailing.active()) {
            finish(run, "skipped", 0, "paused");
            return;
        }
        List<Long> recipients = recipients(run, mailing);
        Set<Long> recorded = runs.recordedUsers(runId);
        for (long user : recipients) {
            if (!recorded.contains(user)) dispatcher.deliver(run, mailing, user);
        }
        Map<String, Integer> counts = runs.statusCounts(runId);
        int failed = counts.getOrDefault("failed", 0);
        int sent = counts.getOrDefault("sent", 0) + counts.getOrDefault("queued", 0);
        String outcome = failed == 0 ? "done" : sent == 0 ? "failed" : "partial";
        finish(run, outcome, recipients.size(), null);
        log.info(
                "report_mailing_run mailing={} run={} kind={} outcome={} recipients={}",
                mailing.id(),
                runId,
                run.kind(),
                outcome,
                recipients.size());
    }

    private List<Long> recipients(RunRow run, MailingRow mailing) {
        if ("test".equals(run.kind())) {
            return run.requestedBy() == null ? List.of() : List.of(run.requestedBy());
        }
        return mailings.recipients(List.of(mailing.id())).getOrDefault(mailing.id(), List.of()).stream()
                .map(RecipientRow::id)
                .toList();
    }

    private void finish(RunRow run, String outcome, int recipients, @Nullable String error) {
        Map<String, Integer> counts = runs.statusCounts(run.id());
        transaction.executeWithoutResult(status -> runs.finish(
                run.id(),
                outcome,
                recipients,
                counts.getOrDefault("sent", 0) + counts.getOrDefault("queued", 0),
                counts.getOrDefault("skipped", 0),
                counts.getOrDefault("failed", 0),
                error));
        metrics.run(outcome);
    }
}
