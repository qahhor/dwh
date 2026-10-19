package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaFailure;
import com.smartup24.cms.instance.common.web.RequestZone;
import com.smartup24.cms.instance.jobs.api.JobHandler;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.md.service.MdPrincipals;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.RunNow;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository.NewRun;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The look of a scheduled automation at the records of its form (ADR-0046), a job of the shared queue for a due slot
 * and the work of a run now: as the author, page by page, every record the author sees is read and judged by the
 * condition; each that meets it gets a run, unique per slot (or request) and record, so a repeated slot queues nothing
 * twice. At most {@value #MAX_RECORDS} records are read; the rest wait for the next slot and the answer says so.
 */
@Component
public class AutomationScans implements JobHandler {
    public static final String JOB = "studio.automation_scan";
    static final int MAX_RECORDS = 1000;
    private static final int PAGE = 100;
    private static final Logger log = LoggerFactory.getLogger(AutomationScans.class);

    private final StudioAutomationRepository automations;
    private final StudioAutomationRunRepository runs;
    private final AutomationForms forms;
    private final AutomationChecks checks;
    private final AutomationJson json;
    private final MdPrincipals principals;
    private final EntityRuntime runtime;
    private final JobQueue jobs;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AutomationScans(
            StudioAutomationRepository automations,
            StudioAutomationRunRepository runs,
            AutomationForms forms,
            AutomationChecks checks,
            AutomationJson json,
            MdPrincipals principals,
            EntityRuntime runtime,
            @Lazy JobQueue jobs,
            PlatformTransactionManager transactions,
            ObjectProvider<Clock> clock) {
        this.automations = automations;
        this.runs = runs;
        this.forms = forms;
        this.checks = checks;
        this.json = json;
        this.principals = principals;
        this.runtime = runtime;
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
        long id = Long.parseLong(String.valueOf(args.get("automationId")));
        scan(id, "scheduled", String.valueOf(args.get("slot")));
    }

    /**
     * Queues a run for every record of the automation's form that meets its condition now.
     *
     * @param kind {@code scheduled} for a slot, {@code manual} for a run now
     * @param key  the slot or the request: a record gets one run per key
     */
    public RunNow scan(long automationId, String kind, String key) {
        Optional<AutomationRow> found = automations.find(automationId);
        if (found.isEmpty() || !found.get().active()) return new RunNow(0, false);
        AutomationRow automation = found.get();
        AutomationSpec spec;
        try {
            spec = checks.check(automation.form(), json.request(automation), AutomationChecks.Context.run());
        } catch (RuntimeException outdated) {
            log.warn("studio_automation_scan_outdated automation={} error={}", automationId, outdated.toString());
            return new RunNow(0, false);
        }
        Optional<AutomationForm> form = forms.find(spec.form());
        if (form.isEmpty() || form.get().archived()) return new RunNow(0, false);
        Instant now = clock.instant();
        List<Long> matching = new ArrayList<>();
        boolean truncated = principals.callAs(
                automation.authorId(),
                () -> RequestZone.with(spec.zone(), () -> collect(spec, form.get(), now, matching)));
        int queued = queue(automation, kind, key, matching);
        log.info(
                "studio_automation_scan automation={} kind={} matching={} queued={} truncated={}",
                automationId,
                kind,
                matching.size(),
                queued,
                truncated);
        return new RunNow(queued, truncated);
    }

    /** The records the author sees that meet the condition; answers whether records were left unread. */
    private boolean collect(AutomationSpec spec, AutomationForm form, Instant now, List<Long> matching) {
        String cursor = null;
        int read = 0;
        while (true) {
            KeysetPage<EntityRecordView> page = runtime.list(form.code(), PAGE, cursor, null, null, null);
            for (EntityRecordView row : page.items()) {
                if (read++ >= MAX_RECORDS) return true;
                if (holds(spec, form, row.id(), now)) matching.add(row.id());
            }
            if (!page.hasMore() || page.nextCursor() == null) return false;
            cursor = page.nextCursor();
        }
    }

    private boolean holds(AutomationSpec spec, AutomationForm form, long recordId, Instant now) {
        Formula condition = spec.condition();
        if (condition == null) return true;
        AutomationRun run =
                new AutomationRun(0, "", 0, "", form, recordId, 1, true, spec.zone(), now, Map.of(), Set.of());
        try {
            run.read(runtime.get(form.code(), recordId));
            return condition.holds(run.values(), run.scope());
        } catch (ApiException | FormulaFailure unread) {
            log.info(
                    "studio_automation_scan_record_skipped form={} record={} error={}",
                    form.code(),
                    recordId,
                    unread.toString());
            return false;
        }
    }

    private int queue(AutomationRow automation, String kind, String key, List<Long> matching) {
        Integer queued = transaction.execute(status -> {
            int count = 0;
            for (long record : matching) {
                String payload = json.write(Map.of("event", "SCHEDULED", "key", key));
                if (runs.insert(new NewRun(
                        automation.id(),
                        kind + ":" + key + ":" + record,
                        kind,
                        AutomationSpec.SCHEDULED,
                        automation.form(),
                        record,
                        1,
                        payload))) {
                    jobs.enqueueOnce(AutomationRunner.JOB, Map.of("form", automation.form(), "recordId", record));
                    count++;
                }
            }
            return count;
        });
        return queued == null ? 0 : queued;
    }

    static @Nullable String slot(@Nullable Instant slot) {
        return slot == null ? null : Long.toString(slot.getEpochSecond());
    }
}
