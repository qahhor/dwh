package com.smartup24.cms.instance.studio.automation;

import static com.smartup24.cms.instance.studio.automation.AutomationChecks.placeholders;
import static com.smartup24.cms.instance.studio.automation.AutomationChecks.problem;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaException;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Transition;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.service.MdSimpleFormLogic;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Action;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.FieldValue;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Recipients;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Texts;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Checks the actions of an automation (ADR-0046): a value is written only to an entered field of its form with a
 * formula of a fitting type over the triggering record; a new record names every required field its form has no
 * default for; a transition is one of the form's; a notification goes on configured channels to somebody, with texts
 * whose placeholders the record has. A deletion leaves nothing to write: its automations only notify.
 */
@Component
public class AutomationActionChecks {
    static final int MAX_TITLE = 200;
    static final int MAX_BODY = 2000;
    static final int MAX_VALUES = 30;

    private final StudioAutomationRepository automations;

    public AutomationActionChecks(StudioAutomationRepository automations) {
        this.automations = automations;
    }

    List<AutomationSpec.Step> check(
            AutomationForm form,
            String kind,
            @Nullable List<Action> asked,
            Map<String, FormulaType> types,
            AutomationChecks.Context context,
            Function<String, Optional<AutomationForm>> lookup,
            List<FieldErrorItem> problems) {
        List<AutomationSpec.Step> steps = new ArrayList<>();
        if (asked == null || asked.isEmpty() || asked.size() > 10) {
            problems.add(problem("actions", "error.studio.automation_actions"));
            return steps;
        }
        for (int i = 0; i < asked.size(); i++) {
            Action action = asked.get(i);
            String at = "actions[" + i + "]";
            String type = action == null || action.type() == null ? "" : action.type();
            if (AutomationSpec.DELETED.equals(kind) && !"notify".equals(type)) {
                problems.add(problem(at + ".type", "error.studio.automation_deleted_action"));
                continue;
            }
            AutomationSpec.Step step = switch (type) {
                case "set_field" -> setFields(form, Objects.requireNonNull(action), types, at, problems);
                case "create_record" -> create(Objects.requireNonNull(action), types, lookup, at, problems);
                case "transition" -> transition(form, Objects.requireNonNull(action), at, problems);
                case "notify" -> notify(form, Objects.requireNonNull(action), context, at, problems);
                default -> {
                    problems.add(problem(at + ".type", "error.studio.automation_action"));
                    yield null;
                }
            };
            if (step != null) steps.add(step);
        }
        return steps;
    }

    private static AutomationSpec.@Nullable Step setFields(
            AutomationForm form,
            Action action,
            Map<String, FormulaType> types,
            String at,
            List<FieldErrorItem> problems) {
        List<AutomationSpec.Assign> values = values(form, action.values(), types, at, problems);
        return values.isEmpty() ? null : new AutomationSpec.SetFields(values);
    }

    private static AutomationSpec.@Nullable Step create(
            Action action,
            Map<String, FormulaType> types,
            Function<String, Optional<AutomationForm>> lookup,
            String at,
            List<FieldErrorItem> problems) {
        Optional<AutomationForm> target = action.form() == null ? Optional.empty() : lookup.apply(action.form());
        if (target.isEmpty()) {
            problems.add(problem(at + ".form", "error.studio.automation_form"));
            return null;
        }
        List<AutomationSpec.Assign> values = values(target.get(), action.values(), types, at, problems);
        Set<String> named = new HashSet<>();
        values.forEach(value -> named.add(value.field()));
        for (Field field : target.get().shown().values()) {
            if (AutomationForm.needsValue(field) && field.formula() == null && !named.contains(field.id())) {
                problems.add(problem(at + ".values", "error.studio.automation_target_required", field.id()));
            }
        }
        return values.isEmpty()
                ? null
                : new AutomationSpec.CreateRecord(target.get().code(), values);
    }

    private static List<AutomationSpec.Assign> values(
            AutomationForm target,
            @Nullable List<FieldValue> asked,
            Map<String, FormulaType> types,
            String at,
            List<FieldErrorItem> problems) {
        List<AutomationSpec.Assign> values = new ArrayList<>();
        if (asked == null || asked.isEmpty() || asked.size() > MAX_VALUES) {
            problems.add(problem(at + ".values", "error.studio.automation_values"));
            return values;
        }
        Set<String> seen = new HashSet<>();
        for (int j = 0; j < asked.size(); j++) {
            FieldValue value = asked.get(j);
            String path = at + ".values[" + j + "]";
            String id = value == null ? null : value.field();
            Field field = id == null ? null : target.entered(id);
            if (field == null || !seen.add(id)) {
                problems.add(problem(path + ".field", "error.studio.automation_field", id));
                continue;
            }
            Formula formula = formula(value.formula(), types, field, path + ".formula", problems);
            if (formula != null) values.add(new AutomationSpec.Assign(field.id(), field.type(), formula));
        }
        return values;
    }

    private static @Nullable Formula formula(
            @Nullable String source,
            Map<String, FormulaType> types,
            Field field,
            String at,
            List<FieldErrorItem> problems) {
        if (source == null || source.isBlank() || source.length() > Formula.MAX_LENGTH) {
            problems.add(problem(at, "error.studio.automation_formula"));
            return null;
        }
        try {
            Formula formula = Formula.compile(source, types);
            if (!fits(field, formula.type())) {
                problems.add(new FormulaException("result", 1, formula.type().wire()).fieldError(at));
                return null;
            }
            return formula;
        } catch (FormulaException problem) {
            problems.add(problem.fieldError(at));
            return null;
        }
    }

    /** Whether a formula of {@code type} gives a value the field keeps: a key for a user or a form's record too. */
    public static boolean fits(Field field, FormulaType type) {
        boolean keyed = "user".equals(field.type())
                || ("ref".equals(field.type())
                        && field.target() != null
                        && "form".equals(Objects.requireNonNull(field.target()).kind()));
        if (keyed) return type == FormulaType.REF || type == FormulaType.NUMBER || type == FormulaType.NULL;
        return MdSimpleFormLogic.fits(field.type(), type);
    }

    private static AutomationSpec.@Nullable Step transition(
            AutomationForm form, Action action, String at, List<FieldErrorItem> problems) {
        Transition transition =
                action.transition() == null ? null : form.transitions().get(action.transition());
        if (transition == null) {
            problems.add(problem(at + ".transition", "error.studio.automation_transition", action.transition()));
            return null;
        }
        String comment = action.comment() == null || action.comment().isBlank() ? null : action.comment();
        if (comment != null && comment.length() > 1000) {
            problems.add(problem(at + ".comment", "error.studio.automation_text"));
        } else if (comment == null && "required".equals(transition.comment())) {
            problems.add(problem(at + ".comment", "error.studio.automation_comment_required"));
        }
        placeholders(comment, AutomationChecks.placeholderNames(form), at + ".comment", problems);
        return new AutomationSpec.RunTransition(transition.code(), comment);
    }

    private AutomationSpec.@Nullable Step notify(
            AutomationForm form,
            Action action,
            AutomationChecks.Context context,
            String at,
            List<FieldErrorItem> problems) {
        int before = problems.size();
        List<String> channels = action.channels() == null ? List.of() : List.copyOf(new HashSet<>(action.channels()));
        if (channels.isEmpty()) problems.add(problem(at + ".channels", "error.studio.automation_channels"));
        for (String channel : channels) {
            if (!context.channels().contains(channel)) {
                problems.add(problem(at + ".channels", "error.studio.automation_channel", channel));
            }
        }
        Recipients recipients = action.recipients();
        List<String> fields = recipients == null || recipients.fields() == null ? List.of() : recipients.fields();
        boolean author = recipients != null && Boolean.TRUE.equals(recipients.author());
        List<Long> users = recipients == null || recipients.users() == null ? List.of() : recipients.users();
        List<Right> rights = recipients == null || recipients.rights() == null ? List.of() : recipients.rights();
        if (fields.isEmpty() && !author && users.isEmpty() && rights.isEmpty()) {
            problems.add(problem(at + ".recipients", "error.studio.automation_recipients"));
        }
        recipients(form, fields, users, rights, context, at + ".recipients", problems);
        var names = AutomationChecks.placeholderNames(form);
        texts(action.title(), MAX_TITLE, names, at + ".title", problems);
        texts(action.body(), MAX_BODY, names, at + ".body", problems);
        if (problems.size() > before) return null;
        return new AutomationSpec.Notify(
                channels,
                fields,
                author,
                users,
                rights,
                Objects.requireNonNull(action.title()),
                Objects.requireNonNull(action.body()));
    }

    private void recipients(
            AutomationForm form,
            List<String> fields,
            List<Long> users,
            List<Right> rights,
            AutomationChecks.Context context,
            String at,
            List<FieldErrorItem> problems) {
        List<String> userFields = form.userFields();
        for (int k = 0; k < fields.size(); k++) {
            if (k >= 10 || !userFields.contains(fields.get(k))) {
                problems.add(problem(at + ".fields[" + k + "]", "error.studio.automation_field", fields.get(k)));
            }
        }
        if (users.size() > 50) problems.add(problem(at + ".users", "error.studio.automation_users"));
        if (context.strict() && !users.isEmpty()) {
            Set<Long> active = automations.activeUsers(users).keySet();
            for (int k = 0; k < users.size(); k++) {
                if (!active.contains(users.get(k))) {
                    problems.add(problem(at + ".users[" + k + "]", "error.studio.automation_user", users.get(k)));
                }
            }
        }
        if (rights.size() > 5) problems.add(problem(at + ".rights", "error.studio.automation_rights"));
        Set<String> catalog = context.rights();
        for (int k = 0; k < rights.size(); k++) {
            Right right = rights.get(k);
            String pair = right == null ? "" : right.form() + "." + right.action();
            if (right == null
                    || right.form() == null
                    || right.action() == null
                    || (catalog != null && !catalog.contains(pair))) {
                problems.add(problem(at + ".rights[" + k + "]", "error.studio.automation_right", pair));
            }
        }
    }

    private static void texts(
            @Nullable Texts texts, int max, Set<String> names, String at, List<FieldErrorItem> problems) {
        if (texts == null || texts.ru() == null || texts.ru().isBlank()) {
            problems.add(problem(at + ".ru", "error.studio.automation_text"));
            return;
        }
        check(texts.ru(), max, names, at + ".ru", problems);
        check(texts.uz(), max, names, at + ".uz", problems);
        check(texts.en(), max, names, at + ".en", problems);
    }

    private static void check(
            @Nullable String text, int max, Set<String> names, String at, List<FieldErrorItem> problems) {
        if (text == null) return;
        if (text.length() > max) {
            problems.add(problem(at, "error.studio.automation_text"));
            return;
        }
        placeholders(text, names, at, problems);
    }
}
