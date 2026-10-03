package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import org.jspecify.annotations.Nullable;

/**
 * How a form field behaves beyond its value's rules (ADR-0032, 4.3–4.4), as {@code form-meta} gives it to the form.
 *
 * @param readonly     when a save cannot change it, or null
 * @param defaultValue the value a new record takes without it, or null
 * @param visibleWhen  when the form shows it, or null for always
 * @param computed     the server computes it (ADR-0032, 4.1): read-only, never sent
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record FormFlags(
        @Nullable FieldReadonly readonly,
        @Nullable FieldDefault defaultValue,
        @Nullable FieldCondition visibleWhen,
        boolean computed) {

    /** An editable, always shown field without a default. */
    public static final FormFlags NONE = new FormFlags(null, null, null, false);

    public FormFlags {
        if (computed && (readonly == null || readonly.mode() != FieldReadonly.Mode.ALWAYS)) {
            throw new IllegalArgumentException("A computed field is always read-only");
        }
    }
}
