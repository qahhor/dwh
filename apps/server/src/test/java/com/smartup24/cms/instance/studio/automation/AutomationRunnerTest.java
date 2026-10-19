package com.smartup24.cms.instance.studio.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository.RunRow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0046: the runs of a record are taken in order, closed with their outcome, retried or failed. */
class AutomationRunnerTest {
    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    private static final String FORM = "studio.orders";
    private static final String DECLARATION = """
            {"formatVersion": 3, "kind": "simple-form",
             "module": {"code": "studio", "title": {"ru": "Ф", "uz": "F", "en": "F"}},
             "entity": {"name": "orders", "title": {"ru": "З", "uz": "B", "en": "O"}},
             "fields": [{"id": "note", "type": "text", "labels": {"ru": "П", "uz": "P", "en": "N"},
               "required": false, "listed": true, "maxLength": 9}]}
            """;

    private final StudioAutomationRunRepository runs = mock(StudioAutomationRunRepository.class);
    private final StudioAutomationRepository automations = mock(StudioAutomationRepository.class);
    private final AutomationForms forms = mock(AutomationForms.class);
    private final AutomationChecks checks = mock(AutomationChecks.class);
    private final AutomationExecutor executor = mock(AutomationExecutor.class);
    private final AutomationRunner runner;

    @SuppressWarnings("unchecked")
    AutomationRunnerTest() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        ObjectProvider<Clock> clock = mock(ObjectProvider.class);
        when(clock.getIfAvailable(any(Supplier.class))).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry = mock(ObjectProvider.class);
        runner = new AutomationRunner(
                runs,
                automations,
                forms,
                checks,
                new AutomationJson(JsonMapper.shared()),
                executor,
                new AutomationMetrics(registry),
                transactions,
                clock);
    }

    private static RunRow run(String trigger, int attempts, boolean due) {
        return new RunRow(
                5,
                3,
                "key",
                "event",
                trigger,
                FORM,
                9,
                1,
                "{\"old\": {\"note\": \"было\"}, \"changed\": [\"note\"]}",
                "queued",
                null,
                null,
                "[]",
                attempts,
                NOW,
                NOW,
                null,
                due);
    }

    private static AutomationRow automation(boolean active) {
        return new AutomationRow(
                3,
                FORM,
                "Проба",
                "saved",
                "{\"kind\": \"saved\"}",
                null,
                "[]",
                "UTC",
                active,
                null,
                1,
                "Автор",
                1,
                NOW,
                NOW);
    }

    private void queued(RunRow run) {
        when(runs.nextQueued(FORM, 9)).thenReturn(Optional.of(run), Optional.empty());
    }

    private void ready() {
        when(automations.find(3)).thenReturn(Optional.of(automation(true)));
        when(checks.check(eq(FORM), any(), any()))
                .thenReturn(new AutomationSpec(
                        FORM,
                        new AutomationSpec.Trigger("saved", Set.of(), Set.of(), Set.of(), null),
                        null,
                        List.of(),
                        ZoneId.of("UTC")));
        when(forms.find(FORM))
                .thenReturn(Optional.of(new AutomationForm(
                        FORM, JsonMapper.shared().readValue(DECLARATION, Declaration.class), false)));
    }

    @Test
    @DisplayName("ADR-0046: nothing queued or not due yet ends the drain; a gone or paused automation skips its run")
    void skipsWhatCannotRun() {
        when(runs.nextQueued(FORM, 9)).thenReturn(Optional.empty());
        assertThat(runner.drain(FORM, 9)).isZero();
        when(runs.nextQueued(FORM, 9)).thenReturn(Optional.of(run("saved", 0, false)));
        assertThat(runner.drain(FORM, 9)).isZero();

        queued(run("saved", 0, true));
        when(automations.find(3)).thenReturn(Optional.empty());
        assertThat(runner.drain(FORM, 9)).isEqualTo(1);
        verify(runs).finish(eq(5L), eq("skipped"), eq("deleted"), isNull(), anyString());

        queued(run("saved", 0, true));
        when(automations.find(3)).thenReturn(Optional.of(automation(false)));
        runner.run(Map.of("form", FORM, "recordId", 9));
        verify(runs).finish(eq(5L), eq("skipped"), eq("paused"), isNull(), anyString());
    }

    @Test
    @DisplayName("ADR-0046: an automation that no longer fits its form fails; a form that went is skipped")
    void outdatedAutomationsAndGoneForms() {
        queued(run("saved", 0, true));
        when(automations.find(3)).thenReturn(Optional.of(automation(true)));
        when(checks.check(eq(FORM), any(), any()))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.automation_invalid"));
        runner.drain(FORM, 9);
        verify(runs).finish(eq(5L), eq("failed"), eq("definition"), anyString(), anyString());

        ready();
        when(forms.find(FORM)).thenReturn(Optional.empty());
        queued(run("saved", 0, true));
        runner.drain(FORM, 9);
        verify(runs).finish(eq(5L), eq("skipped"), eq("form_gone"), isNull(), anyString());
    }

    @Test
    @DisplayName("ADR-0046: a done run is closed; a refused one after its rollback; a transient one waits for a retry")
    void outcomesAreRecorded() {
        ready();
        when(executor.perform(any(), any(), anyBoolean()))
                .thenReturn(new AutomationExecutor.Outcome("done", null, null, List.of(), true, false, false));
        queued(run("deleted", 0, true));
        runner.drain(FORM, 9);
        verify(runs).finish(eq(5L), eq("done"), isNull(), isNull(), anyString());

        when(executor.perform(any(), any(), anyBoolean()))
                .thenReturn(AutomationExecutor.Outcome.failed(
                                "action_failed", AutomationExecutor.internal(), List.of(), true)
                        .rolledBack());
        queued(run("saved", 0, true));
        runner.drain(FORM, 9);
        verify(runs).retryAt(eq(5L), eq(NOW.plusSeconds(30)), eq("action_failed"), anyString());

        queued(run("saved", 2, true));
        runner.drain(FORM, 9);
        verify(runs).finish(eq(5L), eq("failed"), eq("action_failed"), anyString(), anyString());

        when(executor.perform(any(), any(), anyBoolean())).thenThrow(new IllegalStateException("database"));
        queued(run("saved", 1, true));
        runner.drain(FORM, 9);
        verify(runs).retryAt(eq(5L), eq(NOW.plusSeconds(120)), eq("internal"), anyString());
    }

    @Test
    @DisplayName("ADR-0046: a failure before any run is taken is the job's failure")
    void aFailureBeforeARunIsRethrown() {
        doThrow(new IllegalStateException("lock")).when(runs).lockRecord(FORM, 9);
        assertThatThrownBy(() -> runner.drain(FORM, 9)).isInstanceOf(IllegalStateException.class);
        verify(runs, never()).retryAt(anyLong(), any(), any(), any());
        assertThat(runner.code()).isEqualTo(AutomationRunner.JOB);
    }
}
