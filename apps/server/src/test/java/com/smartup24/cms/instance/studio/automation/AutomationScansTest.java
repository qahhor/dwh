package com.smartup24.cms.instance.studio.automation;

import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.FORM;
import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.NOW;
import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.row;
import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.md.service.MdPrincipals;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository.RecordKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0046: a scheduled look reads the records as the author; the tick starts what is due and samples the queue. */
class AutomationScansTest {
    private static final String SCHEDULED =
            "{\"kind\": \"scheduled\", \"schedule\": {\"frequency\": \"daily\", \"time\": \"09:00\"}}";

    private final StudioAutomationRepository automations = mock(StudioAutomationRepository.class);
    private final StudioAutomationRunRepository runs = mock(StudioAutomationRunRepository.class);
    private final AutomationForms forms = mock(AutomationForms.class);
    private final AutomationChecks checks = mock(AutomationChecks.class);
    private final MdPrincipals principals = mock(MdPrincipals.class);
    private final EntityRuntime runtime = mock(EntityRuntime.class);
    private final JobQueue jobs = mock(JobQueue.class);
    private final AutomationScans scans = new AutomationScans(
            automations,
            runs,
            forms,
            checks,
            AutomationFixtures.JSON,
            principals,
            runtime,
            jobs,
            AutomationFixtures.transactions(),
            AutomationFixtures.clock());

    AutomationScansTest() {
        doAnswer(call -> ((Supplier<?>) call.getArgument(1)).get())
                .when(principals)
                .callAs(anyLong(), any());
    }

    private void ready(@Nullable String condition) {
        when(automations.find(3)).thenReturn(Optional.of(row(3, "scheduled", SCHEDULED, true)));
        Formula formula = condition == null ? null : Formula.compile(condition, Map.of("note", FormulaType.TEXT));
        when(checks.check(eq(FORM), any(), any()))
                .thenReturn(new AutomationSpec(
                        FORM,
                        new AutomationSpec.Trigger("scheduled", Set.of(), Set.of(), Set.of(), null),
                        formula,
                        List.of(),
                        ZoneId.of("UTC")));
        when(forms.find(FORM)).thenReturn(Optional.of(AutomationFixtures.form(false)));
    }

    private static KeysetPage<EntityRecordView> page(@Nullable String next, long... ids) {
        List<EntityRecordView> items = new ArrayList<>();
        for (long id : ids) items.add(view(id, Map.of()));
        return new KeysetPage<>(items, next, next != null, ids.length);
    }

    @Test
    @DisplayName("ADR-0046: a gone, paused or outdated automation and a gone or archived form look at nothing")
    void nothingToLookAt() {
        when(automations.find(3)).thenReturn(Optional.empty());
        assertThat(scans.scan(3, "manual", "k").queued()).isZero();
        when(automations.find(3)).thenReturn(Optional.of(row(3, "scheduled", SCHEDULED, false)));
        assertThat(scans.scan(3, "manual", "k").queued()).isZero();
        when(automations.find(3)).thenReturn(Optional.of(row(3, "scheduled", SCHEDULED, true)));
        when(checks.check(eq(FORM), any(), any()))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.automation_invalid"));
        assertThat(scans.scan(3, "manual", "k").queued()).isZero();
        ready(null);
        when(forms.find(FORM)).thenReturn(Optional.empty());
        assertThat(scans.scan(3, "manual", "k").queued()).isZero();
        when(forms.find(FORM)).thenReturn(Optional.of(AutomationFixtures.form(true)));
        assertThat(scans.scan(3, "manual", "k").queued()).isZero();
        assertThat(scans.code()).isEqualTo(AutomationScans.JOB);
        assertThat(AutomationScans.slot(null)).isNull();
        assertThat(AutomationScans.slot(NOW)).isEqualTo(Long.toString(NOW.getEpochSecond()));
    }

    @Test
    @DisplayName(
            "ADR-0046: page by page, the records meeting the condition get one run each; unreadable ones are skipped")
    void recordsMeetingTheConditionGetARun() {
        ready("len(note) / (len(note) - 1) > 0");
        when(runtime.list(eq(FORM), anyInt(), isNull(), any(), any(), any())).thenReturn(page("next", 1, 2));
        when(runtime.list(eq(FORM), anyInt(), eq("next"), any(), any(), any())).thenReturn(page(null, 3));
        when(runtime.get(FORM, 1)).thenReturn(view(1, Map.of("note", "ok")));
        when(runtime.get(FORM, 2)).thenReturn(view(2, Map.of("note", "x")));
        when(runtime.get(FORM, 3))
                .thenThrow(ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_not_found"));
        when(runs.insert(any())).thenReturn(true);
        var answer = scans.scan(3, "manual", "k");
        assertThat(answer.queued()).isEqualTo(1);
        assertThat(answer.truncated()).isFalse();
        verify(jobs).enqueueOnce(AutomationRunner.JOB, Map.of("form", FORM, "recordId", 1L));
    }

    @Test
    @DisplayName("ADR-0046: at most a thousand records are read; a run already queued for the slot is not queued again")
    void aLookIsBounded() {
        ready(null);
        long[] ids = new long[AutomationScans.MAX_RECORDS + 1];
        for (int i = 0; i < ids.length; i++) ids[i] = i + 1;
        when(runtime.list(eq(FORM), anyInt(), any(), any(), any(), any())).thenReturn(page(null, ids));
        when(runs.insert(any())).thenReturn(false);
        scans.run(Map.of("automationId", 3, "slot", "1760860800"));
        var answer = scans.scan(3, "scheduled", "1760860800");
        assertThat(answer.truncated()).isTrue();
        assertThat(answer.queued()).isZero();
        verify(runs, times(2 * AutomationScans.MAX_RECORDS)).insert(any());
    }

    @Test
    @DisplayName(
            "ADR-0046: the tick starts the due looks and record jobs; an unreadable schedule waits; the queue's age is kept")
    void theTickStartsWhatIsDue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutomationScheduler scheduler = new AutomationScheduler(
                automations,
                runs,
                AutomationFixtures.JSON,
                AutomationFixtures.metrics(registry),
                jobs,
                AutomationFixtures.transactions(),
                AutomationFixtures.clock());
        when(automations.lockDue(NOW, AutomationScheduler.BATCH))
                .thenReturn(List.of(row(3, "scheduled", SCHEDULED, true), row(4, "scheduled", "{", true)));
        when(runs.dueRecords(eq(NOW), any(), eq(AutomationScheduler.BATCH)))
                .thenReturn(List.of(new RecordKey(FORM, 9)));
        when(runs.oldestQueued()).thenReturn(Optional.of(NOW.minusSeconds(90)));
        assertThat(scheduler.tick()).isEqualTo(2);
        verify(automations).moveNextRun(eq(3L), any());
        assertThat(registry.get("smc.automation.queue.oldest").gauge().value()).isEqualTo(90);
        when(automations.lockDue(NOW, AutomationScheduler.BATCH)).thenReturn(List.of());
        when(runs.dueRecords(eq(NOW), any(), anyInt())).thenReturn(List.of());
        when(runs.oldestQueued()).thenReturn(Optional.empty());
        scheduler.run(Map.of());
        assertThat(registry.get("smc.automation.queue.oldest").gauge().value()).isZero();
        assertThat(scheduler.code()).isEqualTo(AutomationScheduler.JOB);
    }

    @Test
    @DisplayName("ADR-0046: without a registry the meters are silent; a run's latency is timed when it is known")
    void metersWithAndWithoutARegistry() {
        AutomationMetrics silent = AutomationFixtures.metrics(null);
        silent.run("done", Duration.ofSeconds(1));
        silent.withheld("self");
        silent.oldestQueued(Duration.ofSeconds(-5));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutomationMetrics metrics = AutomationFixtures.metrics(registry);
        metrics.run("done", null);
        metrics.run("done", Duration.ofSeconds(-1));
        metrics.run("failed", Duration.ofSeconds(2));
        assertThat(registry.get("smc.automation.runs")
                        .tag("outcome", "done")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(registry.get("smc.automation.run").timer().count()).isEqualTo(1);
    }
}
