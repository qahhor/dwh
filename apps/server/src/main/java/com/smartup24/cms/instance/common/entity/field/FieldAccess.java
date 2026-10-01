package com.smartup24.cms.instance.common.entity.field;

import org.jspecify.annotations.Nullable;

/**
 * The rights on one field (ADR-0032, 5.2; plan 10/10, item 5.3): without {@code requires} the field does not exist for
 * the viewer — it is absent from {@code form-meta}, {@code query-meta}, the records read, the export, the history and a
 * webhook's data, and a value sent for it is refused as an unknown field; without {@code readonlyUnless} it is
 * read-only — shown, but a changed value is refused. Declared with
 * {@code .requires("md.users", "view_contacts")} and {@code .readonlyUnless("notes", "update")}; applied by
 * {@code EntityFieldRights}.
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

    /** The same rights, the field existing only for holders of {@code form.action}. */
    public FieldAccess requires(String form, String action) {
        return new FieldAccess(form, action, readonlyForm, readonlyAction);
    }

    /** The same rights, the field written only by holders of {@code form.action}. */
    public FieldAccess readonlyUnless(String form, String action) {
        return new FieldAccess(requiredForm, requiredAction, form, action);
    }

    /** Whether the field exists only for holders of a right: a viewer without it never learns its value. */
    public boolean restricted() {
        return requiredForm != null;
    }

    /** Whether writing the field needs a right of its own. */
    public boolean guardsWriting() {
        return readonlyForm != null;
    }
}
