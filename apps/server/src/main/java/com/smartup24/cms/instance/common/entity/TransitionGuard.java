package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.hook.EntityRule;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import com.smartup24.cms.platform.api.entity.hook.RuleErrors;

/**
 * A rule of a transition that also decides whether the transition is offered (ADR-0043): a condition of a live form or
 * a rule that the record's author may not take it. The record's actions leave out a transition whose guard does not
 * admit the record as the transition would leave it; the transition itself checks the same rule and stays the
 * authority. A rule of a declared entity that is no guard keeps its transitions offered, as before.
 */
public interface TransitionGuard extends EntityRule {

    /** Whether the record, as the transition would leave it, meets the rule. */
    default boolean admits(EntityValues record) {
        RuleErrors errors = new RuleErrors();
        check(record, null, errors);
        return errors.items().isEmpty();
    }
}
