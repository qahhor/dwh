package com.smartup24.cms.instance.studio.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.service.MdPrincipals;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Texts;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.ActionResult;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0046: a run as its author — the record, the condition, the actions in order, every refusal in words. */
class AutomationExecutorTest {
    private static final String FORM = "studio.orders";
    private static final String DECLARATION = """
            {"formatVersion": 3, "kind": "simple-form",
             "module": {"code": "studio", "title": {"ru": "Ф", "uz": "F", "en": "F"}},
             "entity": {"name": "orders", "title": {"ru": "З", "uz": "B", "en": "O"}},
             "fields": [{"id": "note", "type": "text", "labels": {"ru": "П", "uz": "P", "en": "N"},
               "required": false, "listed": true, "maxLength": 9}]}
            """;

    private final MdPrincipals principals = mock(MdPrincipals.class);
    private final EntityRuntime runtime = mock(EntityRuntime.class);
    private final AutomationActions actions = mock(AutomationActions.class);
    private final StudioAutomationRepository automations = mock(StudioAutomationRepository.class);
    private final AutomationExecutor executor = new AutomationExecutor(principals, runtime, actions, automations);

    AutomationExecutorTest() {
        doAnswer(call -> ((Supplier<?>) call.getArgument(1)).get())
                .when(principals)
                .callAs(anyLong(), any());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", 9);
        record.put("revision", 2);
        record.put("note", "x");
        when(runtime.get(FORM, 9)).thenReturn(new EntityRecordView(record, List.of()));
    }

    private static AutomationRun run(boolean archived, boolean dry) {
        AutomationForm form =
                new AutomationForm(FORM, JsonMapper.shared().readValue(DECLARATION, Declaration.class), archived);
        return new AutomationRun(
                3, "Проба", 1, "run-1", form, 9, 0, dry, ZoneId.of("UTC"), Instant.now(), Map.of(), Set.of());
    }

    private static AutomationSpec spec(String condition, AutomationSpec.Step... steps) {
        Formula formula = condition == null ? null : Formula.compile(condition, Map.of("note", FormulaType.TEXT));
        return new AutomationSpec(
                FORM,
                new AutomationSpec.Trigger("saved", Set.of(), Set.of(), Set.of(), null),
                formula,
                List.of(steps),
                ZoneId.of("UTC"));
    }

    private static AutomationSpec.Notify notice() {
        return new AutomationSpec.Notify(
                List.of("in_app"),
                List.of(),
                true,
                List.of(),
                List.of(),
                new Texts("t", null, null),
                new Texts("b", null, null));
    }

    @Test
    @DisplayName("ADR-0046: an archived form skips; the condition decides; the actions apply in order")
    void runsTheActionsWhenTheConditionHolds() {
        assertThat(executor.perform(spec(null), run(true, false), false).reason())
                .isEqualTo("form_archived");
        assertThat(executor.perform(spec("note = \"y\""), run(false, false), false)
                        .reason())
                .isEqualTo("condition");
        AutomationExecutor.Outcome failed = executor.perform(spec("len(note) / 0 > 1"), run(false, false), false);
        assertThat(failed.reason()).isEqualTo("condition_failed");
        doReturn(new ActionResult(0, "notify", "done", null, null, null, null, null, null, null))
                .when(actions)
                .apply(anyInt(), any(), any(), anyBoolean());
        AutomationExecutor.Outcome done =
                executor.perform(spec("note = \"x\"", notice(), notice()), run(false, false), false);
        assertThat(done.status()).isEqualTo("done");
        assertThat(done.results()).hasSize(2);
        assertThat(executor.perform(spec(null, notice()), run(false, true), true)
                        .status())
                .isEqualTo("would_apply");
    }

    @Test
    @DisplayName("ADR-0046: a refused action fails the run and skips the rest; a stale revision may be retried")
    void aRefusedActionFailsTheRun() {
        ApiException locked = ApiException.validation(
                "error.common.record_fields_invalid",
                List.of(
                        FieldErrorItem.keyed("review", "readonly", "error.field.readonly", Map.of("a", 1)),
                        new FieldErrorItem("note", "too_long", "Too long")));
        doThrow(locked).when(actions).apply(anyInt(), any(), any(), anyBoolean());
        AutomationExecutor.Outcome failed = executor.perform(spec(null, notice(), notice()), run(false, false), false);
        assertThat(failed.rollback()).isTrue();
        assertThat(failed.transientFailure()).isFalse();
        assertThat(failed.results()).extracting(ActionResult::status).containsExactly("failed", "skipped");
        assertThat(failed.results().getFirst().reason()).isEqualTo("refused");
        assertThat(failed.error().fields()).hasSize(2);

        for (ErrorCode code : List.of(
                ErrorCode.CONFLICT, ErrorCode.NOT_FOUND, ErrorCode.PERMISSION_DENIED, ErrorCode.INTERNAL_ERROR)) {
            doThrow(new ApiException(code, "error.common.x")).when(actions).apply(anyInt(), any(), any(), anyBoolean());
            AutomationExecutor.Outcome outcome = executor.perform(spec(null, notice()), run(false, false), false);
            assertThat(outcome.transientFailure()).isEqualTo(code == ErrorCode.CONFLICT);
        }
        doThrow(new IllegalStateException("db")).when(actions).apply(anyInt(), any(), any(), anyBoolean());
        AutomationExecutor.Outcome broken = executor.perform(spec(null, notice()), run(false, false), false);
        assertThat(broken.results().getFirst().reason()).isEqualTo("internal");
        assertThat(broken.transientFailure()).isTrue();
    }

    @Test
    @DisplayName("ADR-0046: a blocked author, a record out of reach and a gone record end the run in words")
    void theAuthorAndTheRecordDecide() {
        doThrow(ApiException.permissionDenied("md.profile", "view"))
                .when(principals)
                .callAs(anyLong(), any());
        assertThat(executor.perform(spec(null), run(false, false), false).reason())
                .isEqualTo("author_inactive");
        doThrow(new ApiException(ErrorCode.CONFLICT, "error.common.x"))
                .when(principals)
                .callAs(anyLong(), any());
        assertThatThrownBy(() -> executor.perform(spec(null), run(false, false), false))
                .isInstanceOf(ApiException.class);
        doAnswer(call -> ((Supplier<?>) call.getArgument(1)).get())
                .when(principals)
                .callAs(anyLong(), any());

        doThrow(ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_not_found"))
                .when(runtime)
                .get(FORM, 9);
        when(automations.recordExists(FORM, 9)).thenReturn(true, false);
        assertThat(executor.perform(spec(null), run(false, false), false).reason())
                .isEqualTo("author_access");
        assertThat(executor.perform(spec(null), run(false, false), false).reason())
                .isEqualTo("record_gone");
        doThrow(new ApiException(ErrorCode.CONFLICT, "error.common.x"))
                .when(runtime)
                .get(FORM, 9);
        assertThatThrownBy(() -> executor.perform(spec(null), run(false, false), false))
                .isInstanceOf(ApiException.class);
    }
}
