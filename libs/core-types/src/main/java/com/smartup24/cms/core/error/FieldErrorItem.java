package com.smartup24.cms.core.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Field-level validation error detail.
 *
 * <p>{@code messageKey} and {@code params} name the text in the i18n catalogs (plan 10/10, item 3.1), as they do for
 * the problem itself; {@code message} is that text rendered by the server in the request's language. A keyed item
 * carries its key as the message until the error handler renders it. {@code message} stays for the clients that read
 * it; an item without a key is one whose text comes from a framework (bean validation).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldErrorItem(String field, String code, String message, String messageKey, Map<String, Object> params) {

    public FieldErrorItem {
        params = params == null || params.isEmpty() ? null : Map.copyOf(params);
    }

    /** An item whose text is already written: bean validation's own message. */
    public FieldErrorItem(String field, String code, String message) {
        this(field, code, message, null, null);
    }

    /** An item whose text is the catalog entry {@code messageKey}, rendered in the request's language. */
    public static FieldErrorItem keyed(String field, String code, String messageKey) {
        return new FieldErrorItem(field, code, messageKey, messageKey, null);
    }

    /** As {@link #keyed(String, String, String)}, with the values of the text's {@code {placeholders}}. */
    public static FieldErrorItem keyed(String field, String code, String messageKey, Map<String, ?> params) {
        return new FieldErrorItem(field, code, messageKey, messageKey, Map.<String, Object>copyOf(params));
    }

    /** The same item under another field name (a nested object's error reported on its parent). */
    public FieldErrorItem at(String otherField) {
        return new FieldErrorItem(otherField, code, message, messageKey, params);
    }

    /** The same item with its text rendered. */
    public FieldErrorItem withMessage(String rendered) {
        return new FieldErrorItem(field, code, rendered, messageKey, params);
    }
}
