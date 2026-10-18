package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityNotice;
import com.smartup24.cms.instance.common.security.DataScopes;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.State;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Transition;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdSimpleFormDocumentLogic;
import com.smartup24.cms.instance.studio.repository.StudioNumberRepository;
import com.smartup24.cms.platform.api.actor.AuditActor;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.hook.EntityActionCall;
import com.smartup24.cms.platform.api.entity.hook.EntityOperation;
import com.smartup24.cms.platform.api.entity.hook.EntitySave;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The hooks of a live form's document (ADR-0043) without a database: the number on creation or on the status that
 * gives it, the comment of a transition, and who is told when a document reaches a status.
 */
class StudioDocumentStepsTest {
    private static final Labels ORDER = new Labels("Заказ", "Buyurtma", "Order");

    private final StudioDocuments documents = mock(StudioDocuments.class);

    @Test
    void aNumberIsGivenOnCreationOrOnTheStatusThatGivesIt() {
        when(documents.number(eq(7L), any())).thenReturn("ZK-1");
        Numbering onCreate = new Numbering("ZK-{N}", "never", null, null);
        EntitySave created = save(EntityOperation.CREATE, null, Map.of());
        hooks(onCreate, workflow()).beforeSave(created);
        verify(created.values()).set(MdSimpleFormDocumentLogic.NUMBER, "ZK-1");

        Numbering onApproval = new Numbering("ZK-{N}", "never", "approved", null);
        EntitySave draft = save(EntityOperation.CREATE, null, Map.of());
        hooks(onApproval, workflow()).beforeSave(draft);
        verify(draft.values(), never()).set(anyString(), any());
        EntitySave plain = save(EntityOperation.CREATE, null, Map.of());
        hooks(null, workflow()).beforeSave(plain);
        verify(plain.values(), never()).set(anyString(), any());

        EntitySave approved = save(EntityOperation.ACTION, "approve", Map.of());
        hooks(onApproval, workflow()).beforeSave(approved);
        verify(approved.values()).set(MdSimpleFormDocumentLogic.NUMBER, "ZK-1");
        EntitySave numbered = save(EntityOperation.ACTION, "approve", Map.of());
        when(numbered.values().get(MdSimpleFormDocumentLogic.NUMBER)).thenReturn("ZK-0");
        hooks(onApproval, workflow()).beforeSave(numbered);
        verify(numbered.values(), never()).set(eq(MdSimpleFormDocumentLogic.NUMBER), any());

        EntitySave changed = save(EntityOperation.UPDATE, null, Map.of());
        hooks(onCreate, workflow()).beforeSave(changed);
        verifyNoInteractions(changed.values());
        EntitySave unknown = save(EntityOperation.ACTION, "archive", Map.of());
        hooks(onCreate, workflow()).beforeSave(unknown);
        hooks(onCreate, null).beforeSave(unknown);
        verifyNoInteractions(unknown.values());
        assertThat(hooks(null, null).entity()).isEqualTo("studio.orders");
    }

    @Test
    void theCommentOfATransitionIsAskedTrimmedAndBounded() {
        EntitySave missing = save(EntityOperation.ACTION, "reject", Map.of("comment", "  "));
        hooks(null, workflow()).beforeSave(missing);
        verify(missing).reject("comment", "required", "error.studio.comment_required", Map.of());

        EntitySave tooLong = save(EntityOperation.ACTION, "reject", Map.of("comment", "я".repeat(1001)));
        hooks(null, workflow()).beforeSave(tooLong);
        verify(tooLong).reject("comment", "too_long", "error.field.too_long", Map.of("max", 1000));

        EntitySave given = save(EntityOperation.ACTION, "reject", Map.of("comment", "  Дорого "));
        hooks(null, workflow()).beforeSave(given);
        verify(given.values()).set(MdSimpleFormDocumentLogic.COMMENT, "Дорого");

        EntitySave ignored = save(EntityOperation.ACTION, "submit", Map.of("comment", "лишнее"));
        hooks(null, workflow()).beforeSave(ignored);
        verify(ignored.values()).set(MdSimpleFormDocumentLogic.COMMENT, null);

        EntitySave plain = mock(EntitySave.class);
        EntityValues values = mock(EntityValues.class);
        when(plain.operation()).thenReturn(EntityOperation.ACTION);
        when(plain.action()).thenReturn("approve");
        when(plain.values()).thenReturn(values);
        hooks(null, workflow()).beforeSave(plain);
        verify(values).set(MdSimpleFormDocumentLogic.COMMENT, null);
    }

    @Test
    void aDocumentThatReachesAStatusTellsThePeopleItWaitsFor() {
        EntitySave submitted = save(EntityOperation.ACTION, "submit", Map.of());
        when(submitted.id()).thenReturn(5L);
        when(submitted.values().get("createdBy")).thenReturn(3L);
        when(submitted.values().get(StudioEntityCompiler.ORG_UNIT)).thenReturn(9);
        when(submitted.values().get("docNumber")).thenReturn("ZK-5");
        hooks(null, workflow()).afterSave(submitted);
        ArgumentCaptor<StudioDocuments.Step> step = ArgumentCaptor.forClass(StudioDocuments.Step.class);
        verify(documents).notify(step.capture());
        assertThat(step.getValue().recordTitle()).isEqualTo("ZK-5");
        assertThat(step.getValue().author()).isEqualTo(3L);
        assertThat(step.getValue().orgUnit()).isEqualTo(9L);
        assertThat(step.getValue().state().ru()).isEqualTo("На согласовании");
        assertThat(step.getValue().next()).extracting(Transition::code).containsExactly("approve", "reject");

        var spec = new StudioDocumentHooks.Spec(
                "studio.orders", 7, "all", StudioFormLayout.RECORD_NUMBER, ORDER, null, workflow());
        EntitySave counted = save(EntityOperation.ACTION, "submit", Map.of());
        when(counted.id()).thenReturn(6L);
        when(counted.values().get(StudioFormLayout.RECORD_NUMBER)).thenReturn(12);
        new StudioDocumentHooks(spec, documents).afterSave(counted);
        verify(documents, org.mockito.Mockito.times(2)).notify(step.capture());
        assertThat(step.getValue().recordTitle()).isEqualTo("#12");
        assertThat(step.getValue().author()).isNull();
        EntitySave untitled = save(EntityOperation.ACTION, "submit", Map.of());
        when(untitled.id()).thenReturn(8L);
        hooks(null, workflow()).afterSave(untitled);
        verify(documents, org.mockito.Mockito.times(3)).notify(step.capture());
        assertThat(step.getValue().recordTitle()).isEqualTo("#8");

        EntitySave approved = save(EntityOperation.ACTION, "approve", Map.of());
        when(approved.id()).thenReturn(5L);
        hooks(null, workflow()).afterSave(approved);
        EntitySave created = save(EntityOperation.CREATE, null, Map.of());
        when(created.id()).thenReturn(5L);
        hooks(null, workflow()).afterSave(created);
        EntitySave unsaved = save(EntityOperation.ACTION, "submit", Map.of());
        when(unsaved.id()).thenReturn(null);
        hooks(null, workflow()).afterSave(unsaved);
        EntitySave unknown = save(EntityOperation.ACTION, "archive", Map.of());
        when(unknown.id()).thenReturn(5L);
        hooks(null, workflow()).afterSave(unknown);
        verify(documents, org.mockito.Mockito.times(3)).notify(any());

        assertThat(spec.state("lost")).isEqualTo(ORDER);
        assertThat(new StudioDocumentHooks.Spec("c", 1, "all", "name", ORDER, null, null).state("x"))
                .isEqualTo(ORDER);
        assertThat(new StudioDocumentHooks.Spec("c", 1, "all", "name", ORDER, null, null).notifying("x"))
                .isEmpty();
    }

    @Test
    void theNoticeReachesThoseWhoMayTakeTheStepAndSeeTheRecord() {
        MdPermissionService permissions = mock(MdPermissionService.class);
        DataScopes scopes = mock(DataScopes.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        StudioNumberRepository numbers = mock(StudioNumberRepository.class);
        var service = new StudioDocuments(
                numbers,
                permissions,
                scopes,
                events,
                Clock.fixed(Instant.parse("2026-03-05T10:00:00Z"), ZoneOffset.UTC));
        when(permissions.holders(eq("studio.orders"), eq(Set.of("approve", "reject")), anyInt()))
                .thenReturn(List.of(1L, 2L, 3L, 4L));
        when(scopes.unitVisible(4L, 9L)).thenReturn(true);
        List<Transition> next = workflow().transitions().subList(1, 3);

        when(permissions.holders(eq("studio.orders"), eq(Set.of("submit")), anyInt()))
                .thenReturn(List.of(2L, 4L));
        List<Transition> submit = workflow().transitions().subList(0, 1);

        service.notify(step("owner", 3L, 9L, next));
        service.notify(step("org_unit", 3L, null, next));
        service.notify(step("all", 3L, 9L, List.of()));
        verify(events, never()).publishEvent(any());
        service.notify(step("org_unit", 3L, 9L, next));
        service.notify(step("all", 3L, 9L, next));
        service.notify(step("owner", 2L, null, submit));
        ArgumentCaptor<EntityNotice> notice = ArgumentCaptor.forClass(EntityNotice.class);
        verify(events, org.mockito.Mockito.times(3)).publishEvent(notice.capture());
        // The actor (1) is never told, nor the author (3) of a step only others may take.
        assertThat(notice.getAllValues().get(0).recipients()).containsExactly(4L);
        assertThat(notice.getAllValues().get(1).recipients()).containsExactly(2L, 4L);
        assertThat(notice.getAllValues().get(2).recipients()).containsExactly(2L);
        assertThat(notice.getAllValues().get(0).params().get("uz")).containsEntry("form", "Buyurtma");
        assertThat(notice.getAllValues().get(0).sourceCode()).isEqualTo("studio-step-studio.orders-5-pending");

        when(numbers.next(anyLong(), anyString())).thenReturn(41L);
        assertThat(service.number(7, new Numbering("ZK-{YY}{MM}-{NNN}", "month", null, null)))
                .isEqualTo("ZK-2603-041");
    }

    private StudioDocuments.Step step(String scope, Long author, Long unit, List<Transition> next) {
        return new StudioDocuments.Step(
                "studio.orders",
                "studio.orders",
                scope,
                5,
                author,
                unit,
                1,
                "ZK-5",
                ORDER,
                "pending",
                new Labels("На согласовании", "Kelishuvda", "Pending"),
                next);
    }

    private StudioDocumentHooks hooks(Numbering numbering, Workflow workflow) {
        return new StudioDocumentHooks(
                new StudioDocumentHooks.Spec("studio.orders", 7, "all", "docNumber", ORDER, numbering, workflow),
                documents);
    }

    private static EntitySave save(EntityOperation operation, String action, Map<String, Object> params) {
        EntityActionCall save = mock(EntityActionCall.class);
        EntityValues values = mock(EntityValues.class);
        EntityDefinition entity = mock(EntityDefinition.class);
        when(entity.form()).thenReturn("studio.orders");
        when(save.operation()).thenReturn(operation);
        when(save.action()).thenReturn(action);
        when(save.params()).thenReturn(params);
        when(save.values()).thenReturn(values);
        when(save.entity()).thenReturn(entity);
        when(save.actor()).thenReturn(AuditActor.user(1));
        return save;
    }

    private static Workflow workflow() {
        Labels labels = new Labels("Статус", "Holat", "Status");
        return new Workflow(
                labels,
                List.of(
                        new State("draft", new Labels("Черновик", "Qoralama", "Draft"), "gray", null, null, null),
                        new State(
                                "pending",
                                new Labels("На согласовании", "Kelishuvda", "Pending"),
                                "blue",
                                null,
                                Boolean.TRUE,
                                null),
                        new State(
                                "approved",
                                new Labels("Согласовано", "Tasdiqlandi", "Approved"),
                                "green",
                                true,
                                null,
                                null),
                        new State(
                                "rejected",
                                new Labels("Отклонено", "Rad etildi", "Rejected"),
                                "red",
                                true,
                                null,
                                null)),
                List.of(
                        new Transition("submit", labels, List.of("draft"), "pending", null, "none", null, null),
                        new Transition("approve", labels, List.of("pending"), "approved", null, "optional", true, true),
                        new Transition(
                                "reject", labels, List.of("pending"), "rejected", null, "required", true, true)));
    }
}
