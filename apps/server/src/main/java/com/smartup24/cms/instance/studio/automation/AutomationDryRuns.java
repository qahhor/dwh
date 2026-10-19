package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.DryRun;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A dry run of an automation over one record (ADR-0046): the run as its author — the person trying it — performs it, in
 * a transaction of its own that always rolls back, so the runtime's checks, hooks and rules answer as they would, yet
 * no change, notification, audit row or follow-up run outlives it. The trigger counts as met: {@code old.*} are the
 * record's own values and nothing counts as changed.
 */
@Component
public class AutomationDryRuns {
    private final AutomationExecutor executor;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AutomationDryRuns(
            AutomationExecutor executor, PlatformTransactionManager transactions, ObjectProvider<Clock> clock) {
        this.executor = executor;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setName("automation-dry-run");
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public DryRun run(AutomationSpec spec, AutomationForm form, String name, EntityRecordView record) {
        Map<String, Object> values = new LinkedHashMap<>(record.properties());
        values.remove(EntityRecordView.ACTIONS);
        values.remove(EntityRecordView.LABELS);
        AutomationRun run = new AutomationRun(
                0,
                name,
                Objects.requireNonNull(SecurityContext.getCurrentUserId()),
                "dry-" + UUID.randomUUID(),
                form,
                record.id(),
                1,
                true,
                spec.zone(),
                clock.instant(),
                AutomationEvents.scalars(values),
                Set.of());
        AutomationExecutor.Outcome outcome = Objects.requireNonNull(transaction.execute(status -> {
            status.setRollbackOnly();
            return executor.perform(spec, run, false);
        }));
        return new DryRun(outcome.conditionHolds(), outcome.reason(), outcome.error(), outcome.results());
    }
}
