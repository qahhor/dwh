package com.smartup24.cms.instance.common.entity.field;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The rules a value of a form field must meet (ADR-0032, 3.1), checked by the form before the request and by
 * {@code EntityValidator} on the server. The scale, item count and file rules of plan 10/10, item 5.2 join them.
 *
 * @param minLength shortest text, or null
 * @param maxLength longest text, or null
 * @param min       smallest number, or null
 * @param max       largest number, or null
 * @param pattern   a regular expression the whole text must match, or null
 */
public record FieldRules(
        @Nullable Integer minLength,
        @Nullable Integer maxLength,
        @Nullable BigDecimal min,
        @Nullable BigDecimal max,
        @Nullable String pattern) {

    /** No rules. */
    public static final FieldRules NONE = new FieldRules(null, null, null, null, null);

    public FieldRules {
        if (pattern != null) {
            Pattern.compile(pattern);
        }
    }

    public FieldRules length(@Nullable Integer shortest, @Nullable Integer longest) {
        return new FieldRules(shortest, longest, min, max, pattern);
    }

    public FieldRules range(@Nullable BigDecimal smallest, @Nullable BigDecimal largest) {
        return new FieldRules(minLength, maxLength, smallest, largest, pattern);
    }

    public FieldRules matching(String regex) {
        return new FieldRules(minLength, maxLength, min, max, regex);
    }
}
