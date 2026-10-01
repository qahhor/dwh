package com.smartup24.cms.instance.common.entity.field;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The rules a value of a form field must meet (ADR-0032, 3.1), checked by the form before the request and by
 * {@code EntityValidator} on the server.
 *
 * @param minLength    shortest text, or null
 * @param maxLength    longest text, or null
 * @param min          smallest number or amount, or null
 * @param max          largest number or amount, or null
 * @param pattern      a regular expression the whole text must match, or null
 * @param scale        most digits after the point of a number, or null; money is limited by its currency too
 * @param maxItems     most keys of a multiple reference, or null for the default of 100
 * @param maxBytes     largest file, or null for the upload limit
 * @param contentTypes the content types a file may have; empty takes any (an image takes PNG, JPEG and WebP)
 */
public record FieldRules(
        @Nullable Integer minLength,
        @Nullable Integer maxLength,
        @Nullable BigDecimal min,
        @Nullable BigDecimal max,
        @Nullable String pattern,
        @Nullable Integer scale,
        @Nullable Integer maxItems,
        @Nullable Long maxBytes,
        List<String> contentTypes) {

    /** No rules. */
    public static final FieldRules NONE = new FieldRules(null, null, null, null, null, null, null, null, List.of());

    public FieldRules {
        if (pattern != null) {
            Pattern.compile(pattern);
        }
        contentTypes = contentTypes == null ? List.of() : List.copyOf(contentTypes);
        if ((scale != null && scale < 0) || (maxItems != null && maxItems < 1) || (maxBytes != null && maxBytes < 1)) {
            throw new IllegalArgumentException("A scale is not negative, an item count and a file size are positive");
        }
    }

    public FieldRules length(@Nullable Integer shortest, @Nullable Integer longest) {
        return new FieldRules(shortest, longest, min, max, pattern, scale, maxItems, maxBytes, contentTypes);
    }

    public FieldRules range(@Nullable BigDecimal smallest, @Nullable BigDecimal largest) {
        return new FieldRules(
                minLength, maxLength, smallest, largest, pattern, scale, maxItems, maxBytes, contentTypes);
    }

    public FieldRules matching(String regex) {
        return new FieldRules(minLength, maxLength, min, max, regex, scale, maxItems, maxBytes, contentTypes);
    }

    public FieldRules withScale(int digits) {
        return new FieldRules(minLength, maxLength, min, max, pattern, digits, maxItems, maxBytes, contentTypes);
    }

    public FieldRules withMaxItems(int most) {
        return new FieldRules(minLength, maxLength, min, max, pattern, scale, most, maxBytes, contentTypes);
    }

    public FieldRules withFiles(@Nullable Long largest, List<String> types) {
        return new FieldRules(minLength, maxLength, min, max, pattern, scale, maxItems, largest, types);
    }
}
