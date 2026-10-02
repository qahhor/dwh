package com.smartup24.cms.instance.common.entity.workflow;

import java.util.Objects;
import java.util.Set;

/**
 * A state of a process (ADR-0032, 9.2): its code — a value of the status field — and the dictionary key of its name.
 *
 * @param code     the value of the status field ({@code draft})
 * @param labelKey the dictionary key of its name
 * @param initial  a new record starts in it; exactly one state is initial
 * @param terminal no transition leaves it and the record is only read
 * @param locks    the fields and collections a save cannot change in it, by key
 */
public record EntityState(String code, String labelKey, boolean initial, boolean terminal, Set<String> locks) {

    public EntityState {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(labelKey, "labelKey");
        locks = Set.copyOf(locks);
    }
}
