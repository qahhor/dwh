package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.entity.EntityFormulaReads;
import com.smartup24.cms.instance.common.entity.TransitionGuard;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaFailure;
import com.smartup24.cms.instance.common.formula.FormulaScope;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.RequestLanguage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import com.smartup24.cms.platform.api.entity.hook.RuleErrors;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The rules of a transition of a live form (ADR-0043), checked by the general runtime over the record as the transition
 * leaves it — and asked before a transition is offered ({@link TransitionGuard}): its condition, a formula that must be
 * true (an empty value does not allow it), and that the record's author may not take it. Each refusal is a problem of
 * the whole record naming the transition in the language of the request.
 */
final class StudioTransitionRules {
    private static final Clock CLOCK = Clock.systemUTC();

    /** The record's author, as every record read holds it. */
    static final String AUTHOR = "createdBy";

    private StudioTransitionRules() {}

    /** A transition allowed only while its formula is true over the record. */
    record Condition(String code, Labels labels, Formula formula) implements TransitionGuard {
        @Override
        public void check(EntityValues values, @Nullable EntityValues before, RuleErrors errors) {
            try {
                if (formula.holds(EntityFormulaReads.values(values), FormulaScope.current(CLOCK))) return;
                errors.field(
                        RuleErrors.RECORD,
                        "transition_condition",
                        "error.studio.transition_condition",
                        Map.of("transition", named(labels)));
            } catch (FormulaFailure failure) {
                errors.field(
                        RuleErrors.RECORD,
                        "formula_" + failure.reason(),
                        failure.messageKey(),
                        Map.of("transition", named(labels)));
            }
        }
    }

    /** A transition the record's author may not take: who approves is not who asked (ADR-0043). */
    record NotAuthor(String code, Labels labels) implements TransitionGuard {
        @Override
        public void check(EntityValues values, @Nullable EntityValues before, RuleErrors errors) {
            Long user = SecurityContext.getCurrentUserId();
            Object author = values.get(AUTHOR);
            if (user == null || !(author instanceof Number number) || !Objects.equals(number.longValue(), user)) {
                return;
            }
            errors.field(
                    RuleErrors.RECORD,
                    "transition_author",
                    "error.studio.transition_author",
                    Map.of("transition", named(labels)));
        }
    }

    /** The name of a transition in the language of the request. */
    static String named(Labels labels) {
        return StudioDocuments.label(labels, RequestLanguage.current());
    }
}
