package com.smartup24.cms.instance.studio.automation;

import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.FORM;
import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.NOW;
import static com.smartup24.cms.instance.studio.automation.AutomationFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityChangeImages;
import com.smartup24.cms.instance.common.entity.EntityChangeOrigin;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository.NewRun;
import com.smartup24.cms.platform.api.entity.event.EntityChanged;
import com.smartup24.cms.platform.api.entity.event.EntityEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** ADR-0046: a change queues the runs of the automations it triggers, and the limits hold loops back. */
class AutomationEventsTest {
    private final StudioAutomationRepository automations = mock(StudioAutomationRepository.class);
    private final StudioAutomationRunRepository runs = mock(StudioAutomationRunRepository.class);
    private final JobQueue jobs = mock(JobQueue.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AutomationEvents events = new AutomationEvents(
            automations,
            runs,
            jobs,
            AutomationFixtures.JSON,
            AutomationFixtures.metrics(registry),
            AutomationFixtures.clock(),
            600);

    private static EntityChanged change(
            String entity, EntityEventType type, @Nullable String action, String... fields) {
        return new EntityChanged(entity, entity, 9, 2, type, action, List.of(fields), 1L, NOW, UUID.randomUUID());
    }

    private static EntityChangeImages images(EntityChanged change, @Nullable String from, @Nullable String to) {
        Map<String, Object> before = new HashMap<>();
        if (from != null) before.put("docStatus", from);
        Map<String, Object> after = new HashMap<>();
        if (to != null) after.put("docStatus", to);
        return new EntityChangeImages(change, before, after);
    }

    private double withheld(String reason) {
        var counter = registry.find("smc.automation.events.withheld")
                .tag("reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName(
            "ADR-0046: a change of another module, an archive, a move to the same status or no automation queues nothing")
    void quietChangesQueueNothing() {
        events.changed(images(change("md.users", EntityEventType.CREATED, null), null, null));
        events.changed(images(change(FORM, EntityEventType.ARCHIVED, null), null, null));
        events.changed(images(change(FORM, EntityEventType.ACTION, "touch"), "draft", "draft"));
        events.changed(images(change(FORM, EntityEventType.ACTION, "touch"), "draft", null));
        verifyNoInteractions(automations);
        when(automations.active(eq(FORM), any())).thenReturn(List.of());
        events.changed(images(change(FORM, EntityEventType.DELETED, null), null, null));
        verifyNoInteractions(runs, jobs);
        assertThat(AutomationEvents.kinds(
                        change(FORM, EntityEventType.RESTORED, null),
                        images(change(FORM, EntityEventType.RESTORED, null), null, null)))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "ADR-0046: the fields and the statuses of a trigger choose the automations; their runs carry the old values")
    void triggersChooseTheAutomations() {
        when(automations.active(eq(FORM), any()))
                .thenReturn(List.of(
                        row(1, "updated", "{\"kind\": \"updated\", \"fields\": [\"note\"]}", true),
                        row(2, "updated", "{\"kind\": \"updated\", \"fields\": [\"review\"]}", true),
                        row(3, "saved", "{\"kind\": \"saved\"}", true),
                        row(4, "saved", "{", true),
                        row(5, "deleted", "{\"kind\": \"deleted\"}", true)));
        when(runs.insert(any())).thenReturn(true);
        Map<String, Object> before = new HashMap<>();
        before.put("note", "было");
        before.put("review", true);
        before.put("at", Timestamp.from(NOW));
        before.put("moment", OffsetDateTime.of(2026, 10, 19, 8, 0, 0, 0, ZoneOffset.UTC));
        before.put("items", List.of(Map.of("qty", 1)));
        before.put("custom", Map.of("a", 1));
        before.put("empty", null);
        events.changed(new EntityChangeImages(change(FORM, EntityEventType.UPDATED, null, "note"), before, Map.of()));
        ArgumentCaptor<NewRun> queued = ArgumentCaptor.forClass(NewRun.class);
        verify(runs, times(3)).insert(queued.capture());
        assertThat(queued.getAllValues()).extracting(NewRun::automationId).containsExactly(1L, 3L, 5L);
        assertThat(queued.getValue().payload())
                .contains("\"old\"", "было", "2026-10-19T08:00:00Z")
                .doesNotContain("items", "custom");
        verify(jobs).enqueueOnce(eq(AutomationRunner.JOB), any());

        when(automations.active(eq(FORM), any()))
                .thenReturn(List.of(
                        row(
                                6,
                                "transition",
                                "{\"kind\": \"transition\", \"from\": [\"draft\"], \"to\": [\"pending\"]}",
                                true),
                        row(7, "transition", "{\"kind\": \"transition\", \"to\": [\"draft\"]}", true),
                        row(8, "transition", "{\"kind\": \"transition\", \"from\": [\"draft\"]}", true)));
        events.changed(new EntityChangeImages(
                change(FORM, EntityEventType.ACTION, "submit"), null, Map.of("docStatus", "pending")));
        events.changed(images(change(FORM, EntityEventType.ACTION, "submit"), "draft", "pending"));
        ArgumentCaptor<NewRun> moved = ArgumentCaptor.forClass(NewRun.class);
        verify(runs, times(5)).insert(moved.capture());
        assertThat(moved.getAllValues().subList(3, 5))
                .extracting(NewRun::automationId)
                .containsExactly(6L, 8L);
    }

    @Test
    @DisplayName("ADR-0046: its own changes, a deep chain and the rates hold a run back, counted by reason")
    void limitsHoldRunsBack() {
        when(automations.active(eq(FORM), any())).thenReturn(List.of(row(1, "saved", "{\"kind\": \"saved\"}", true)));
        EntityChangeImages created = images(change(FORM, EntityEventType.CREATED, null), null, null);
        EntityChangeOrigin.with(new EntityChangeOrigin.Origin(1, "Сама", 1), () -> {
            events.changed(created);
            return null;
        });
        EntityChangeOrigin.with(new EntityChangeOrigin.Origin(2, "Другая", 3), () -> {
            events.changed(created);
            return null;
        });
        when(runs.countForRecord(anyLong(), anyLong(), any())).thenReturn(20);
        events.changed(created);
        when(runs.countForRecord(anyLong(), anyLong(), any())).thenReturn(0);
        when(runs.countSince(any())).thenReturn(600);
        events.changed(created);
        when(runs.countSince(any())).thenReturn(0);
        when(runs.insert(any())).thenReturn(false);
        events.changed(created);
        verify(jobs, never()).enqueueOnce(any(), any());
        assertThat(withheld("self")).isEqualTo(1);
        assertThat(withheld("depth")).isEqualTo(1);
        assertThat(withheld("record_rate")).isEqualTo(1);
        assertThat(withheld("installation_rate")).isEqualTo(1);
    }
}
