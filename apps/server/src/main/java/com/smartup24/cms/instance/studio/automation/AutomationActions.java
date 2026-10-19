package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.FormulaFailure;
import com.smartup24.cms.instance.common.formula.FormulaValues;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.ActionResult;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Applies the actions of an automation (ADR-0046) through the general runtime, as the author: the author's rights, data
 * scope and field rights, the locks of the record's status, the rules and the hooks of the form apply as to a person's
 * own request, and every change is audited and published as one. A value is the formula over the record as the earlier
 * actions left it. A failure stops the run: its transaction rolls back every action of the run.
 */
@Component
public class AutomationActions {
    private final EntityRuntime runtime;
    private final AutomationNotices notices;
    private final AutomationTexts texts;
    private final AutomationJson json;

    public AutomationActions(
            EntityRuntime runtime, AutomationNotices notices, AutomationTexts texts, AutomationJson json) {
        this.runtime = runtime;
        this.notices = notices;
        this.texts = texts;
        this.json = json;
    }

    /** Applies the action — in a dry run inside the run's transaction, which never commits — and says what it did. */
    ActionResult apply(int index, AutomationSpec.Step step, AutomationRun run, boolean gone) {
        String status = run.dry() ? "would_apply" : "done";
        return switch (step) {
            case AutomationSpec.SetFields set -> {
                Map<String, @Nullable Object> values = values(set.values(), run);
                EntityRecordView view = runtime.update(
                        run.form().code(), run.recordId(), Revisions.etag(run.revision()), json.body(values));
                run.read(view);
                yield result(index, step, status, values, null, null);
            }
            case AutomationSpec.CreateRecord create -> {
                Map<String, @Nullable Object> values = values(create.values(), run);
                EntityRecordView created = runtime.create(create.form(), json.body(values));
                yield result(index, step, status, values, create.form(), created.id());
            }
            case AutomationSpec.RunTransition move -> {
                Map<String, @Nullable Object> body = new LinkedHashMap<>();
                String comment = move.comment();
                if (comment != null) body.put("comment", texts.fill(comment, "ru", run));
                EntityRecordView view = runtime.action(
                        run.form().code(),
                        run.recordId(),
                        move.code(),
                        Revisions.etag(run.revision()),
                        json.body(body));
                run.read(view);
                yield result(index, step, status, body, null, null);
            }
            case AutomationSpec.Notify notify -> notices.send(index, notify, run, gone);
        };
    }

    /** The values of the fields, each its formula over the record now, as the runtime reads a body. */
    private static Map<String, @Nullable Object> values(Iterable<AutomationSpec.Assign> assigns, AutomationRun run) {
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        for (AutomationSpec.Assign assign : assigns) {
            Object value;
            try {
                value = assign.formula().evaluate(run.values(), run.scope());
            } catch (FormulaFailure failure) {
                throw new ApiException(
                        com.smartup24.cms.core.error.ErrorCode.VALIDATION_FAILED,
                        "error.studio.automation_formula_failed",
                        Map.of("field", assign.field()));
            }
            values.put(assign.field(), body(assign.type(), value));
        }
        return values;
    }

    /** A formula's value as a body of the runtime carries it: a key as a number, a flag as a flag, others as text. */
    static @Nullable Object body(String fieldType, @Nullable Object value) {
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Long key) return key;
        if (("user".equals(fieldType) || "ref".equals(fieldType)) && value instanceof BigDecimal number) {
            try {
                return number.longValueExact();
            } catch (ArithmeticException notAKey) {
                return FormulaValues.text(number);
            }
        }
        return FormulaValues.text(value);
    }

    private static ActionResult result(
            int index,
            AutomationSpec.Step step,
            String status,
            Map<String, @Nullable Object> values,
            @Nullable String form,
            @Nullable Long recordId) {
        Map<String, Object> shown = new LinkedHashMap<>();
        values.forEach((key, value) -> shown.put(key, value == null ? "" : value));
        return new ActionResult(index, step.type(), status, null, shown, form, recordId, null, null, null);
    }
}
