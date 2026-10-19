package com.smartup24.cms.instance.studio.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Action;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.AutomationRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.FieldValue;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Recipients;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Schedule;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Texts;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Trigger;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0046: every part of an automation is checked against its forms and answered at its place. */
class AutomationChecksTest {
    private static final String LABELS = "{\"ru\": \"Т\", \"uz\": \"T\", \"en\": \"T\"}";

    private static final String ORDERS = """
            {"formatVersion": 6, "kind": "simple-form",
             "module": {"code": "studio", "title": %1$s},
             "entity": {"name": "orders", "title": %1$s, "numbering": {"pattern": "З-{NNNN}", "reset": "never"}},
             "fields": [
               {"id": "discount", "type": "number", "labels": %1$s, "required": false, "listed": true},
               {"id": "total", "type": "number", "labels": %1$s, "required": false, "listed": true, "formula": "discount"},
               {"id": "manager", "type": "user", "labels": %1$s, "required": false, "listed": true},
               {"id": "client", "type": "ref", "labels": %1$s, "required": false, "listed": true,
                "target": {"kind": "form", "code": "studio.clients"}},
               {"id": "region", "type": "ref", "labels": %1$s, "required": false, "listed": true,
                "target": {"kind": "list", "code": "regions"}},
               {"id": "review", "type": "bool", "labels": %1$s, "required": false, "listed": true},
               {"id": "note", "type": "text", "labels": %1$s, "required": false, "listed": true, "maxLength": 50},
               {"id": "secret", "type": "text", "labels": %1$s, "required": false, "listed": false, "maxLength": 9,
                "hidden": true}
             ],
             "lines": [{"key": "items", "labels": %1$s, "maxRows": 5,
               "fields": [{"id": "qty", "type": "number", "labels": %1$s, "required": true}]}],
             "workflow": {
               "states": [
                 {"code": "draft", "labels": %1$s, "color": "gray"},
                 {"code": "pending", "labels": %1$s, "color": "blue"}
               ],
               "transitions": [
                 {"code": "submit", "labels": %1$s, "from": ["draft"], "to": "pending", "comment": "required"},
                 {"code": "back", "labels": %1$s, "from": ["pending"], "to": "draft", "comment": "none"}
               ]}}
            """.formatted(LABELS);

    private static final String TASKS = """
            {"formatVersion": 4, "kind": "simple-form",
             "module": {"code": "studio", "title": %1$s},
             "entity": {"name": "tasks", "title": %1$s, "titleField": "ordernum"},
             "fields": [
               {"id": "ordernum", "type": "text", "labels": %1$s, "required": true, "listed": true, "maxLength": 40},
               {"id": "assignee", "type": "user", "labels": %1$s, "required": false, "listed": true},
               {"id": "due", "type": "date", "labels": %1$s, "required": true, "listed": true, "defaultValue": "today"}
             ]}
            """.formatted(LABELS);

    private final AutomationForms forms = mock(AutomationForms.class);
    private final StudioAutomationRepository automations = mock(StudioAutomationRepository.class);
    private final AutomationChecks checks = new AutomationChecks(forms, new AutomationActionChecks(automations));
    private final AutomationChecks.Context strict = new AutomationChecks.Context(
            Set.of("in_app", "email"), Set.of("studio.orders.update", "studio.orders.submit"), true);

    AutomationChecksTest() {
        when(forms.find("studio.orders")).thenReturn(Optional.of(form("studio.orders", ORDERS)));
        when(forms.find("studio.tasks")).thenReturn(Optional.of(form("studio.tasks", TASKS)));
        when(forms.find("studio.none")).thenReturn(Optional.empty());
        when(automations.activeUsers(anyCollection())).thenReturn(Map.of(7L, "Менеджер"));
    }

    private static AutomationForm form(String code, String json) {
        return new AutomationForm(code, JsonMapper.shared().readValue(json, Declaration.class), false);
    }

    private static AutomationRequest request(Trigger trigger, String condition, List<Action> actions) {
        return new AutomationRequest("studio.orders", "Проба", true, trigger, condition, actions, "Asia/Tashkent");
    }

    private static Action action(String type) {
        return new Action(type, null, null, null, null, null, null, null, null);
    }

    private static Action set(String field, String formula) {
        return new Action(
                "set_field", List.of(new FieldValue(field, formula)), null, null, null, null, null, null, null);
    }

    private List<String> problems(AutomationRequest request) {
        return problems(request, strict);
    }

    private List<String> problems(AutomationRequest request, AutomationChecks.Context context) {
        ApiException refused =
                catchThrowableOfType(ApiException.class, () -> checks.check("studio.orders", request, context));
        assertThat(refused).as("refused").isNotNull();
        List<FieldErrorItem> items = refused.getFieldErrors();
        return items == null
                ? List.of()
                : items.stream()
                        .map(item -> item.field() + ":" + item.messageKey())
                        .toList();
    }

    @Test
    @DisplayName("ADR-0046: a valid automation of each trigger and action builds its spec")
    void aValidAutomationBuildsItsSpec() {
        Action notify = new Action(
                "notify",
                null,
                null,
                null,
                null,
                List.of("in_app", "email"),
                new Recipients(List.of("manager"), true, List.of(7L), List.of(new Right("studio.orders", "update"))),
                new Texts("Заказ {docNumber} {form}", "Buyurtma", "Order"),
                new Texts("Сумма {total}", null, null));
        Action create = new Action(
                "create_record",
                List.of(new FieldValue("ordernum", "docNumber"), new FieldValue("assignee", "manager")),
                "studio.tasks",
                null,
                null,
                null,
                null,
                null,
                null);
        Action move = new Action("transition", null, null, "submit", "Готово {note}", null, null, null, null);
        AutomationSpec spec = checks.check(
                "studio.orders",
                request(
                        new Trigger("updated", List.of("discount"), null, null, null),
                        "changed.discount and old.discount < discount",
                        List.of(
                                set("review", "true"),
                                set("client", "1"),
                                set("region", "\"north\""),
                                create,
                                move,
                                notify)),
                strict);
        assertThat(spec.actions()).hasSize(6);
        assertThat(spec.trigger().fields()).containsExactly("discount");
        assertThat(spec.zone().getId()).isEqualTo("Asia/Tashkent");

        AutomationSpec scheduled = checks.check(
                "studio.orders",
                new AutomationRequest(
                        "studio.orders",
                        "Расписание",
                        true,
                        new Trigger("scheduled", null, null, null, new Schedule("weekly", "09:30", 3, null)),
                        null,
                        List.of(notify),
                        null),
                AutomationChecks.Context.run());
        assertThat(scheduled.trigger().schedule()).isNotNull();
        assertThat(scheduled.zone().getId()).isEqualTo("UTC");
        assertThat(AutomationChecks.scheduleOf(
                                new Trigger("scheduled", null, null, null, new Schedule("monthly", "08:00", null, 31)),
                                "Asia/Tashkent")
                        .monthDay())
                .isEqualTo(31);
        AutomationSpec moved = checks.check(
                "studio.orders",
                request(
                        new Trigger("transition", null, List.of("draft"), List.of("pending"), null),
                        null,
                        List.of(notify)),
                strict,
                code -> code.equals("studio.orders") ? forms.find(code) : Optional.empty());
        assertThat(moved.trigger().to()).containsExactly("pending");
    }

    @Test
    @DisplayName("ADR-0046: the name, the zone, the trigger, its parts and the condition are checked at their places")
    void theTriggerAndTheConditionAreChecked() {
        List<String> broken = problems(new AutomationRequest(
                "studio.orders",
                "x".repeat(121),
                true,
                new Trigger(
                        "created",
                        List.of("discount"),
                        Collections.singletonList(null),
                        null,
                        new Schedule("daily", "09:00", null, null)),
                "discount + 1",
                List.of(set("review", "true")),
                "x".repeat(70)));
        assertThat(broken)
                .contains(
                        "name:error.studio.automation_name",
                        "timeZone:error.studio.automation_time_zone",
                        "trigger.fields:error.studio.automation_trigger_part",
                        "trigger.from:error.studio.automation_trigger_part",
                        "trigger.schedule:error.studio.automation_schedule",
                        "condition:error.formula.result");
        List<String> many = new ArrayList<>(Collections.nCopies(21, "discount"));
        assertThat(problems(request(
                        new Trigger("saved", many, null, null, null), "discount >", List.of(set("review", "true")))))
                .contains("trigger.fields:error.studio.automation_trigger_part", "condition:error.formula.syntax");
        assertThat(problems(request(
                        new Trigger("saved", Collections.singletonList(null), null, null, null),
                        null,
                        List.of(set("review", "true")))))
                .contains("trigger.fields[0]:error.studio.automation_name_unknown");
        assertThat(problems(request(null, null, List.of(set("review", "true")))))
                .contains("trigger.kind:error.studio.automation_trigger");
        assertThat(problems(request(new Trigger(null, null, null, null, null), null, List.of(set("review", "true")))))
                .contains("trigger.kind:error.studio.automation_trigger");
        assertThat(problems(request(
                        new Trigger("scheduled", null, null, null, null), null, List.of(set("review", "true")))))
                .contains("trigger.schedule:error.studio.automation_schedule");
        assertThat(problems(request(
                        new Trigger("scheduled", null, null, null, new Schedule("weekly", "09:00", 9, null)),
                        "count(lines.items) > 0",
                        List.of(set("review", "true")))))
                .contains(
                        "trigger.schedule:error.studio.automation_schedule",
                        "condition:error.studio.automation_condition_lines");
        assertThat(problems(request(
                        new Trigger("scheduled", null, null, null, new Schedule(null, "09:00", null, null)),
                        "null",
                        List.of())))
                .contains(
                        "trigger.schedule:error.studio.automation_schedule", "actions:error.studio.automation_actions");
        assertThat(problems(new AutomationRequest(
                        "studio.none",
                        null,
                        true,
                        new Trigger("saved", null, null, null, null),
                        null,
                        List.of(),
                        null)))
                .isNotEmpty();
        ApiException noForm = catchThrowableOfType(
                ApiException.class,
                () -> checks.check(
                        "studio.none", request(new Trigger("saved", null, null, null, null), null, List.of()), strict));
        assertThat(noForm.getFieldErrors()).extracting(FieldErrorItem::field).contains("form");
    }

    @Test
    @DisplayName(
            "ADR-0046: a written field must be entered and fit its formula; a new record names its required fields")
    void valuesAreChecked() {
        List<Action> actions = new ArrayList<>();
        actions.add(null);
        actions.add(action("fly"));
        actions.add(new Action("set_field", null, null, null, null, null, null, null, null));
        actions.add(new Action(
                "set_field",
                Collections.nCopies(31, new FieldValue("review", "true")),
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        actions.add(new Action(
                "set_field",
                java.util.Arrays.asList(
                        null,
                        new FieldValue("secret", "1"),
                        new FieldValue("total", "1"),
                        new FieldValue("review", " "),
                        new FieldValue("note", "x".repeat(1001)),
                        new FieldValue("discount", "\"text\""),
                        new FieldValue("manager", "\"a\""),
                        new FieldValue("client", "true"),
                        new FieldValue("region", "1 +"),
                        new FieldValue("review", "true"),
                        new FieldValue("review", "true")),
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        actions.add(new Action(
                "create_record",
                List.of(new FieldValue("assignee", "manager")),
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        actions.add(new Action(
                "create_record",
                List.of(new FieldValue("assignee", "manager")),
                "studio.tasks",
                null,
                null,
                null,
                null,
                null,
                null));
        List<String> broken = problems(request(new Trigger("saved", null, null, null, null), null, actions));
        assertThat(broken)
                .contains(
                        "actions[1].type:error.studio.automation_action",
                        "actions[2].values:error.studio.automation_values",
                        "actions[3].values:error.studio.automation_values",
                        "actions[4].values[0].field:error.studio.automation_field",
                        "actions[4].values[1].field:error.studio.automation_field",
                        "actions[4].values[2].field:error.studio.automation_field",
                        "actions[4].values[3].formula:error.studio.automation_formula",
                        "actions[4].values[4].formula:error.studio.automation_formula",
                        "actions[4].values[5].formula:error.formula.result",
                        "actions[4].values[6].formula:error.formula.result",
                        "actions[4].values[7].formula:error.formula.result",
                        "actions[4].values[10].field:error.studio.automation_field",
                        "actions[5].form:error.studio.automation_form",
                        "actions[6].values:error.studio.automation_target_required");
        assertThat(problems(
                        request(new Trigger("deleted", null, null, null, null), null, List.of(set("review", "true")))))
                .containsExactly("actions[0].type:error.studio.automation_deleted_action");
        assertThat(problems(request(
                        new Trigger("saved", null, null, null, null),
                        null,
                        Collections.nCopies(11, set("review", "true")))))
                .containsExactly("actions:error.studio.automation_actions");
        assertThat(problems(request(new Trigger("saved", null, null, null, null), null, null)))
                .containsExactly("actions:error.studio.automation_actions");
        assertThat(AutomationActionChecks.fits(
                        form("studio.orders", ORDERS).shown().get("manager"), FormulaType.NUMBER))
                .isTrue();
    }

    @Test
    @DisplayName(
            "ADR-0046: a transition is one of the form's with the comment it takes; a notification reaches somebody")
    void transitionsAndNotificationsAreChecked() {
        Action noTransition = new Action("transition", null, null, null, null, null, null, null, null);
        Action unknown = new Action("transition", null, null, "fly", null, null, null, null, null);
        Action silent = new Action("transition", null, null, "submit", " ", null, null, null, null);
        Action wordy = new Action("transition", null, null, "back", "x".repeat(1001), null, null, null, null);
        Action stranger = new Action("transition", null, null, "back", "{nobody}", null, null, null, null);
        Action nobody = new Action("notify", null, null, null, null, null, null, null, null);
        Action wrong = new Action(
                "notify",
                null,
                null,
                null,
                null,
                List.of("pigeon"),
                new Recipients(
                        new ArrayList<>(Collections.nCopies(11, "discount")),
                        false,
                        new ArrayList<>(Collections.nCopies(51, 9L)),
                        java.util.Arrays.asList(
                                new Right("studio.orders", "fly"),
                                null,
                                new Right(null, "x"),
                                new Right("a", "b"),
                                new Right("c", "d"),
                                new Right("e", "f"))),
                new Texts("ok", "x".repeat(201), "{nobody}"),
                new Texts(" ", null, null));
        Action noTexts = new Action(
                "notify",
                null,
                null,
                null,
                null,
                List.of("in_app"),
                new Recipients(null, true, null, null),
                null,
                null);
        List<String> broken = problems(request(
                new Trigger("saved", null, null, null, null),
                null,
                List.of(noTransition, unknown, silent, wordy, stranger, nobody, wrong, noTexts)));
        assertThat(broken)
                .contains(
                        "actions[0].transition:error.studio.automation_transition",
                        "actions[1].transition:error.studio.automation_transition",
                        "actions[2].comment:error.studio.automation_comment_required",
                        "actions[3].comment:error.studio.automation_text",
                        "actions[4].comment:error.studio.automation_placeholder",
                        "actions[5].channels:error.studio.automation_channels",
                        "actions[5].recipients:error.studio.automation_recipients",
                        "actions[5].title.ru:error.studio.automation_text",
                        "actions[6].channels:error.studio.automation_channel",
                        "actions[6].recipients.fields[0]:error.studio.automation_field",
                        "actions[6].recipients.users:error.studio.automation_users",
                        "actions[6].recipients.users[0]:error.studio.automation_user",
                        "actions[6].recipients.rights:error.studio.automation_rights",
                        "actions[6].recipients.rights[0]:error.studio.automation_right",
                        "actions[6].recipients.rights[1]:error.studio.automation_right",
                        "actions[6].recipients.rights[2]:error.studio.automation_right",
                        "actions[6].title.uz:error.studio.automation_text",
                        "actions[6].title.en:error.studio.automation_placeholder",
                        "actions[6].body.ru:error.studio.automation_text",
                        "actions[7].title.ru:error.studio.automation_text",
                        "actions[7].body.ru:error.studio.automation_text");
        List<String> lenient = problems(
                request(new Trigger("saved", null, null, null, null), null, List.of(wrong)),
                AutomationChecks.Context.run());
        assertThat(lenient).doesNotContain("actions[0].recipients.users[0]:error.studio.automation_user");
    }
}
