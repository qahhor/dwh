package com.smartup24.cms.instance.common.entity.hook;

import org.jspecify.annotations.Nullable;

/**
 * A cross-field rule of an entity (ADR-0032, 6.6): a pure function of the record's values — no SQL, no service; a
 * check with data belongs in {@code beforeSave}. Declared with {@code Entity.rule(name, rule)}; ready rules are in
 * {@link Rules}. Its problems join the problems of the fields in one 422.
 */
@FunctionalInterface
public interface EntityRule {

    /**
     * Reports the rule's problems with the record.
     *
     * @param values the record as the save will write it
     * @param before the record before the save, or null for a create
     * @param errors where the rule reports its problems
     */
    void check(EntityValues values, @Nullable EntityValues before, RuleErrors errors);
}
