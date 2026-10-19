package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityChangeOrigin;
import com.smartup24.cms.instance.common.entity.runtime.EntityRuntime;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaFailure;
import com.smartup24.cms.instance.common.web.RequestZone;
import com.smartup24.cms.instance.md.service.MdPrincipals;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.ActionResult;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.FieldProblem;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Problem;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Performs one run of an automation over one record (ADR-0046), in the caller's transaction: as the author — whose
 * rights, data scope and field rights the runtime checks again now — with every change naming the automation and its
 * place in a chain. The record is read as the author reads it; the condition is judged over it, the values before the
 * triggering change and what the change changed; the actions follow in order. An archived form or a gone record skips
 * the run; a refusal of the author or of an action fails it and rolls back the actions before it.
 */
@Component
public class AutomationExecutor {
    private final MdPrincipals principals;
    private final EntityRuntime runtime;
    private final AutomationActions actions;
    private final StudioAutomationRepository automations;

    public AutomationExecutor(
            MdPrincipals principals,
            EntityRuntime runtime,
            AutomationActions actions,
            StudioAutomationRepository automations) {
        this.principals = principals;
        this.runtime = runtime;
        this.actions = actions;
        this.automations = automations;
    }

    /**
     * How a run ended: {@code done}, {@code skipped} or {@code failed} with the reason, the problem and what each action
     * did; {@code transientFailure} tells whether another attempt may succeed, {@code rollback} that the run's
     * transaction must roll back — an action or a read was refused — and the outcome be recorded after it.
     */
    public record Outcome(
            String status,
            @Nullable String reason,
            @Nullable Problem error,
            List<ActionResult> results,
            boolean conditionHolds,
            boolean transientFailure,
            boolean rollback) {
        static Outcome skipped(String reason) {
            return new Outcome("skipped", reason, null, List.of(), false, false, false);
        }

        static Outcome failed(String reason, @Nullable Problem error, List<ActionResult> results, boolean retry) {
            return new Outcome("failed", reason, error, results, true, retry, false);
        }

        /** The same outcome, to be recorded after the run's transaction rolled back. */
        Outcome rolledBack() {
            return new Outcome(status, reason, error, results, conditionHolds, transientFailure, true);
        }
    }

    /**
     * Performs the run; an outcome with {@link Outcome#rollback()} asks the caller to roll its transaction back.
     *
     * @param gone the record was deleted: the run reads the values the deletion left
     */
    public Outcome perform(AutomationSpec spec, AutomationRun run, boolean gone) {
        if (run.form().archived()) return Outcome.skipped("form_archived");
        try {
            return principals.callAs(
                    run.authorId(),
                    () -> EntityChangeOrigin.with(
                            new EntityChangeOrigin.Origin(run.automationId(), run.name(), depthOf(run)),
                            () -> RequestZone.with(spec.zone(), () -> body(spec, run, gone))));
        } catch (ApiException refused) {
            if (refused.getErrorCode() != ErrorCode.PERMISSION_DENIED) throw refused;
            return Outcome.failed("author_inactive", problem(refused), List.of(), false)
                    .rolledBack();
        }
    }

    private Outcome body(AutomationSpec spec, AutomationRun run, boolean gone) {
        Outcome unread = read(run, gone);
        if (unread != null) return unread;
        Formula condition = spec.condition();
        try {
            if (condition != null && !condition.holds(run.values(), run.scope())) {
                return new Outcome("skipped", "condition", null, List.of(), false, false, false);
            }
        } catch (FormulaFailure failure) {
            Problem error = new Problem(failure.messageKey(), Map.of(), List.of());
            return Outcome.failed("condition_failed", error, List.of(), false);
        }
        List<ActionResult> results = new ArrayList<>();
        List<AutomationSpec.Step> steps = spec.actions();
        for (int index = 0; index < steps.size(); index++) {
            try {
                results.add(actions.apply(index, steps.get(index), run, gone));
            } catch (RuntimeException failure) {
                Problem error = failure instanceof ApiException api ? problem(api) : internal();
                results.add(new ActionResult(
                        index,
                        steps.get(index).type(),
                        "failed",
                        reason(failure),
                        null,
                        null,
                        null,
                        null,
                        null,
                        error));
                for (int rest = index + 1; rest < steps.size(); rest++) {
                    results.add(new ActionResult(
                            rest,
                            steps.get(rest).type(),
                            "skipped",
                            "previous_failed",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
                }
                return Outcome.failed("action_failed", error, results, retryable(failure))
                        .rolledBack();
            }
        }
        return new Outcome(run.dry() ? "would_apply" : "done", null, null, results, true, false, false);
    }

    /** Reads the record as the author; null when it was read, else why the run does not go on. */
    private @Nullable Outcome read(AutomationRun run, boolean gone) {
        if (gone) return null;
        try {
            run.read(runtime.get(run.form().code(), run.recordId()));
            return null;
        } catch (ApiException unseen) {
            if (unseen.getErrorCode() != ErrorCode.NOT_FOUND && unseen.getErrorCode() != ErrorCode.PERMISSION_DENIED) {
                throw unseen;
            }
            // The refused read marked the run's transaction for rollback: the outcome is recorded after it.
            Outcome outcome = automations.recordExists(run.form().code(), run.recordId())
                    ? Outcome.failed("author_access", problem(unseen), List.of(), false)
                    : Outcome.skipped("record_gone");
            return outcome.rolledBack();
        }
    }

    private static int depthOf(AutomationRun run) {
        return Math.max(1, run.depth());
    }

    /** Whether another attempt may succeed: a stale revision or a failure of the database, not a refusal. */
    static boolean retryable(Throwable failure) {
        if (failure instanceof ApiException api) return api.getErrorCode() == ErrorCode.CONFLICT;
        return true;
    }

    private static String reason(Throwable failure) {
        if (!(failure instanceof ApiException api)) return "internal";
        return switch (api.getErrorCode()) {
            case VALIDATION_FAILED -> "refused";
            case PERMISSION_DENIED -> "author_access";
            case NOT_FOUND -> "not_found";
            case CONFLICT -> "conflict";
            default -> "refused";
        };
    }

    /** The problem of a refusal as the run log keeps it. */
    static Problem problem(ApiException refused) {
        List<FieldProblem> fields = new ArrayList<>();
        List<FieldErrorItem> items = refused.getFieldErrors();
        if (items != null) {
            for (FieldErrorItem item : items) {
                String key = item.messageKey() == null ? item.code() : item.messageKey();
                fields.add(new FieldProblem(item.field(), key, item.params() == null ? Map.of() : item.params()));
            }
        }
        return new Problem(refused.getMessageKey(), refused.getParams(), fields);
    }

    static Problem internal() {
        return new Problem("error.studio.automation_internal", Map.of(), List.of());
    }
}
