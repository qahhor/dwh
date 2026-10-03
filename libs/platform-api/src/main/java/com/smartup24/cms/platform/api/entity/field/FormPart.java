package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What an entity field is on the form (ADR-0032, 3.1): whether a value must be given, the rules it must meet, when it
 * cannot be changed, the value a new record takes without it and when it is shown (ADR-0032, 4.3–4.4).
 *
 * @param required     a value must be given (while the field is shown)
 * @param rules        length, range, pattern, scale, item count and file rules
 * @param readonly     when a save cannot change it, or null when it can
 * @param defaultValue the value a new record takes without it, or null
 * @param visibleWhen  the condition under which the form shows it, or null when it always does; a hidden field is not
 *                     required and its value is not kept
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record FormPart(
        boolean required,
        FieldRules rules,
        @Nullable FieldReadonly readonly,
        @Nullable FieldDefault defaultValue,
        @Nullable FieldCondition visibleWhen) {

    /** An optional field without rules. */
    public static final FormPart OPTIONAL = new FormPart(false, FieldRules.NONE, null, null, null);

    public FormPart {
        Objects.requireNonNull(rules, "rules");
        if (required && readonly != null && readonly.mode() == FieldReadonly.Mode.ALWAYS && defaultValue == null) {
            throw new IllegalArgumentException("A field nobody writes is required only with a default");
        }
    }

    public FormPart(boolean required, FieldRules rules) {
        this(required, rules, null, null, null);
    }

    public FormPart asRequired() {
        return new FormPart(true, rules, readonly, defaultValue, visibleWhen);
    }

    public FormPart withRules(FieldRules changed) {
        return new FormPart(required, changed, readonly, defaultValue, visibleWhen);
    }
}
