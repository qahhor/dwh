package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaException;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.common.schedule.CalendarSchedule;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.AutomationRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Schedule;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Trigger;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Checks an automation against the active versions of the forms it names (ADR-0046) and builds its
 * {@link AutomationSpec}: every problem of the request is one 422, each addressed to its part ({@code trigger.kind},
 * {@code actions[1].values[0].formula}). The same check runs for every run over the stored automation, so a run follows
 * the forms as they are then.
 */
@Component
public class AutomationChecks {
    /** The trigger kinds. */
    public static final Set<String> KINDS = Set.of(
            AutomationSpec.CREATED,
            AutomationSpec.UPDATED,
            AutomationSpec.SAVED,
            AutomationSpec.TRANSITION,
            AutomationSpec.DELETED,
            AutomationSpec.SCHEDULED);

    static final String INVALID = "error.studio.automation_invalid";
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");
    private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

    private final AutomationForms forms;
    private final AutomationActionChecks actions;

    public AutomationChecks(AutomationForms forms, AutomationActionChecks actions) {
        this.forms = forms;
        this.actions = actions;
    }

    /**
     * What the checks may take as given.
     *
     * @param channels the channels this installation delivers on
     * @param rights   the rights of the permission catalog ({@code form.action}), or null not to check them
     * @param strict   a save: fixed users must be active; a run skips the inactive ones instead
     */
    public record Context(Set<String> channels, @Nullable Set<String> rights, boolean strict) {
        /** What a run takes as given: anything the save accepted. */
        public static Context run() {
            return new Context(Set.of("in_app", "email", "telegram"), null, false);
        }
    }

    /** The automation of the live form {@code formCode} as {@code request} describes it; one 422 with every problem. */
    public AutomationSpec check(String formCode, AutomationRequest request, Context context) {
        return check(formCode, request, context, forms::find);
    }

    /**
     * The same check against the forms {@code lookup} gives: the stored ones, or a form's next version before it is
     * activated.
     */
    public AutomationSpec check(
            String formCode,
            AutomationRequest request,
            Context context,
            Function<String, Optional<AutomationForm>> lookup) {
        List<FieldErrorItem> problems = new ArrayList<>();
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > 120) problems.add(problem("name", "error.studio.automation_name"));
        Optional<AutomationForm> found = lookup.apply(formCode);
        if (found.isEmpty()) {
            problems.add(problem("form", "error.studio.automation_form"));
            throw invalid(problems);
        }
        AutomationForm form = found.get();
        ZoneId zone = zone(request.timeZone(), problems);
        AutomationSpec.Trigger trigger = trigger(form, request.trigger(), zone, problems);
        boolean change = AutomationSpec.CHANGES.contains(trigger.kind());
        Map<String, FormulaType> types = form.types(change);
        Formula condition = condition(request.condition(), types, trigger.kind(), problems);
        List<AutomationSpec.Step> steps =
                actions.check(form, trigger.kind(), request.actions(), types, context, lookup, problems);
        if (!problems.isEmpty()) throw invalid(problems);
        return new AutomationSpec(form.code(), trigger, condition, steps, zone);
    }

    private static ZoneId zone(@Nullable String asked, List<FieldErrorItem> problems) {
        if (asked == null || asked.isBlank()) return UTC;
        if (asked.length() > 64 || !ZoneId.getAvailableZoneIds().contains(asked)) {
            problems.add(problem("timeZone", "error.studio.automation_time_zone"));
            return UTC;
        }
        return ZoneId.of(asked);
    }

    private static AutomationSpec.Trigger trigger(
            AutomationForm form, @Nullable Trigger asked, ZoneId zone, List<FieldErrorItem> problems) {
        String kind = asked == null || asked.kind() == null ? "" : asked.kind();
        if (asked == null || !KINDS.contains(kind)) {
            problems.add(problem("trigger.kind", "error.studio.automation_trigger"));
            return new AutomationSpec.Trigger(kind, Set.of(), Set.of(), Set.of(), null);
        }
        boolean fieldsAllowed = AutomationSpec.UPDATED.equals(kind) || AutomationSpec.SAVED.equals(kind);
        Set<String> fields = names(
                asked.fields(), "trigger.fields", fieldsAllowed, form.shown().keySet(), problems);
        boolean transition = AutomationSpec.TRANSITION.equals(kind);
        if (transition && form.states().isEmpty()) {
            problems.add(problem("trigger.kind", "error.studio.automation_no_statuses"));
        }
        Set<String> states = form.states().keySet();
        Set<String> from = names(asked.from(), "trigger.from", transition, states, problems);
        Set<String> to = names(asked.to(), "trigger.to", transition, states, problems);
        CalendarSchedule schedule = null;
        if (AutomationSpec.SCHEDULED.equals(kind)) {
            schedule = schedule(asked.schedule(), zone, problems);
        } else if (asked.schedule() != null) {
            problems.add(problem("trigger.schedule", "error.studio.automation_schedule"));
        }
        return new AutomationSpec.Trigger(kind, fields, from, to, schedule);
    }

    /** The names of a trigger's list, each one of {@code known}; a list the kind does not take is a problem. */
    private static Set<String> names(
            @Nullable List<String> asked,
            String at,
            boolean allowed,
            Set<String> known,
            List<FieldErrorItem> problems) {
        if (asked == null || asked.isEmpty()) return Set.of();
        if (!allowed) {
            problems.add(problem(at, "error.studio.automation_trigger_part"));
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < asked.size() && i < 20; i++) {
            String name = asked.get(i);
            if (name == null || !known.contains(name)) {
                problems.add(problem(at + "[" + i + "]", "error.studio.automation_name_unknown", name));
            } else {
                names.add(name);
            }
        }
        if (asked.size() > 20) problems.add(problem(at, "error.studio.automation_trigger_part"));
        return names;
    }

    private static @Nullable CalendarSchedule schedule(
            @Nullable Schedule asked, ZoneId zone, List<FieldErrorItem> problems) {
        if (asked == null || asked.time() == null || !TIME.matcher(asked.time()).matches()) {
            problems.add(problem("trigger.schedule", "error.studio.automation_schedule"));
            return null;
        }
        String frequency = asked.frequency() == null ? "" : asked.frequency();
        try {
            Integer weekday = CalendarSchedule.WEEKLY.equals(frequency) ? asked.weekday() : null;
            Integer monthDay = CalendarSchedule.MONTHLY.equals(frequency) ? asked.monthDay() : null;
            return new CalendarSchedule(frequency, LocalTime.parse(asked.time()), zone, weekday, monthDay);
        } catch (IllegalArgumentException | DateTimeParseException wrong) {
            problems.add(problem("trigger.schedule", "error.studio.automation_schedule", wrong.getMessage()));
            return null;
        }
    }

    /** The schedule of a stored trigger in its automation's time zone; a trigger without a valid one throws. */
    public static CalendarSchedule scheduleOf(Trigger trigger, String timeZone) {
        Schedule asked = trigger.schedule();
        if (asked == null || asked.time() == null) throw new IllegalArgumentException("schedule");
        String frequency = asked.frequency() == null ? "" : asked.frequency();
        Integer weekday = CalendarSchedule.WEEKLY.equals(frequency) ? asked.weekday() : null;
        Integer monthDay = CalendarSchedule.MONTHLY.equals(frequency) ? asked.monthDay() : null;
        return new CalendarSchedule(frequency, LocalTime.parse(asked.time()), ZoneId.of(timeZone), weekday, monthDay);
    }

    private static @Nullable Formula condition(
            @Nullable String source, Map<String, FormulaType> types, String kind, List<FieldErrorItem> problems) {
        if (source == null || source.isBlank()) return null;
        try {
            Formula formula = Formula.compile(source, types);
            if (formula.type() != FormulaType.BOOL && formula.type() != FormulaType.NULL) {
                problems.add(new FormulaException("result", 1, formula.type().wire()).fieldError("condition"));
                return null;
            }
            boolean lines = formula.fields().keySet().stream().anyMatch(name -> name.startsWith("lines."));
            if (lines && AutomationSpec.SCHEDULED.equals(kind)) {
                problems.add(problem("condition", "error.studio.automation_condition_lines"));
                return null;
            }
            return formula;
        } catch (FormulaException problem) {
            problems.add(problem.fieldError("condition"));
            return null;
        }
    }

    /** The placeholders of a text that the record does not have, each a problem at {@code at}. */
    static void placeholders(@Nullable String text, Set<String> known, String at, List<FieldErrorItem> problems) {
        if (text == null) return;
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (!known.contains(matcher.group(1))) {
                problems.add(problem(at, "error.studio.automation_placeholder", matcher.group(1)));
                return;
            }
        }
    }

    /** The names a text of a message may hold: the fields, the number and the status of the record, the form. */
    static Set<String> placeholderNames(AutomationForm form) {
        Set<String> names = new LinkedHashSet<>();
        form.types(false).keySet().stream().filter(name -> !name.contains(".")).forEach(names::add);
        for (Field field : form.shown().values()) {
            if ("file".equals(field.type())) names.remove(field.id());
        }
        names.add("form");
        return names;
    }

    static FieldErrorItem problem(String at, String key) {
        return FieldErrorItem.keyed(at, "automation_invalid", key);
    }

    static FieldErrorItem problem(String at, String key, @Nullable Object name) {
        return FieldErrorItem.keyed(at, "automation_invalid", key, Map.of("name", String.valueOf(name)));
    }

    static ApiException invalid(List<FieldErrorItem> problems) {
        return ApiException.validation(INVALID, problems);
    }
}
