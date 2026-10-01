package com.smartup24.cms.instance.common.entity.field;

import org.jspecify.annotations.Nullable;

/**
 * The rights on one field (ADR-0032, 5.2): without {@code requires} the field does not exist for the viewer, without
 * {@code readonlyUnless} it is read-only. Today the list applies {@code requires} as a registry field does (ADR-0016);
 * the form, the record, the export, the history and the webhook apply both with plan 10/10, item 5.3.
 *
 * @param requiredForm   the form of the right the field needs to exist, or null when it is open
 * @param requiredAction the action of that right
 * @param readonlyForm   the form of the right the field needs to be written, or null when anyone who sees it may
 * @param readonlyAction the action of that right
 */
public record FieldAccess(
        @Nullable String requiredForm,
        @Nullable String requiredAction,
        @Nullable String readonlyForm,
        @Nullable String readonlyAction) {

    /** A field open to everyone who sees the entity. */
    public static final FieldAccess OPEN = new FieldAccess(null, null, null, null);

    public FieldAccess {
        if ((requiredForm == null) != (requiredAction == null) || (readonlyForm == null) != (readonlyAction == null)) {
            throw new IllegalArgumentException("A field right names both its form and its action");
        }
    }
}
