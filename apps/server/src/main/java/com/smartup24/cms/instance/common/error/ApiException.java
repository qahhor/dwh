package com.smartup24.cms.instance.common.error;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.platform.api.entity.hook.EntityRefusal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The one base of the errors a request can end with (plan 10/10, item 3.1).
 *
 * <p>An error carries a code ({@link ErrorCode}, the contract a client builds on), the key of its text in the i18n
 * catalogs ({@code error.<module>.<name>}) and the values of the text's {@code {placeholders}}. The text itself is
 * rendered for the response by {@code GlobalExceptionHandler} in the request's language, so the code that throws
 * never writes a sentence. A domain exception extends this class; the handler covers every subclass.
 */
public class ApiException extends RuntimeException {

    /** What a message key looks like: dotted lower-case segments, {@code error.md.language_not_found}. */
    public static final Pattern MESSAGE_KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+");

    private final ErrorCode errorCode;
    private final String messageKey;
    private final Map<String, Object> params;
    private final @Nullable List<FieldErrorItem> fieldErrors;

    /** An error whose text is the code's own ({@code error.<code>}). */
    public ApiException(ErrorCode errorCode) {
        this(errorCode, defaultKey(errorCode), Map.of(), null);
    }

    public ApiException(ErrorCode errorCode, String messageKey) {
        this(errorCode, messageKey, Map.of(), null);
    }

    public ApiException(ErrorCode errorCode, String messageKey, Map<String, ?> params) {
        this(errorCode, messageKey, params, null);
    }

    public ApiException(ErrorCode errorCode, String messageKey, List<FieldErrorItem> fieldErrors) {
        this(errorCode, messageKey, Map.of(), fieldErrors);
    }

    public ApiException(
            ErrorCode errorCode, String messageKey, Map<String, ?> params, @Nullable List<FieldErrorItem> fieldErrors) {
        super(messageKey);
        this.errorCode = errorCode;
        this.messageKey = messageKey;
        this.params = Map.copyOf(params);
        this.fieldErrors = fieldErrors;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** The key of the text in the i18n catalogs. */
    public String getMessageKey() {
        return messageKey;
    }

    /** Values of the text's {@code {placeholders}}, by name. */
    public Map<String, Object> getParams() {
        return params;
    }

    public @Nullable List<FieldErrorItem> getFieldErrors() {
        return fieldErrors;
    }

    /**
     * Whether the key looks like a catalog entry. Every caller passes a key (ADR-0021; {@code ErrorTextsTest}); the
     * handler logs one that does not and answers with the code's own text.
     */
    public boolean hasMessageKey() {
        return MESSAGE_KEY.matcher(messageKey).matches();
    }

    @Override
    public String getMessage() {
        return params.isEmpty() ? messageKey : messageKey + " " + params;
    }

    public static String defaultKey(ErrorCode errorCode) {
        return "error." + errorCode.getCode();
    }

    /** Refuses when a lookup found nothing: an existence check that keeps the "not found" of the caller. */
    public static void requirePresent(Optional<?> lookup, Supplier<ApiException> error) {
        if (lookup.isEmpty()) {
            throw error.get();
        }
    }

    public static ApiException unauthorized(String messageKey) {
        return new ApiException(ErrorCode.UNAUTHORIZED, messageKey);
    }

    public static ApiException unauthorized(String messageKey, Map<String, ?> params) {
        return new ApiException(ErrorCode.UNAUTHORIZED, messageKey, params);
    }

    public static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }

    public static ApiException permissionDenied(String form, String action) {
        return new ApiException(
                ErrorCode.PERMISSION_DENIED, "error.permission_denied_action", Map.of("right", form + "." + action));
    }

    public static ApiException notFound(ErrorCode code, String messageKey) {
        return new ApiException(code, messageKey);
    }

    public static ApiException notFound(ErrorCode code, String messageKey, Map<String, ?> params) {
        return new ApiException(code, messageKey, params);
    }

    public static ApiException conflict(ErrorCode code, String messageKey) {
        return new ApiException(code, messageKey);
    }

    public static ApiException conflict(ErrorCode code, String messageKey, Map<String, ?> params) {
        return new ApiException(code, messageKey, params);
    }

    public static ApiException badRequest(ErrorCode code, String messageKey) {
        return new ApiException(code, messageKey);
    }

    public static ApiException badRequest(ErrorCode code, String messageKey, Map<String, ?> params) {
        return new ApiException(code, messageKey, params);
    }

    public static ApiException forbidden(ErrorCode code, String messageKey) {
        return new ApiException(code, messageKey);
    }

    public static ApiException forbidden(ErrorCode code, String messageKey, Map<String, ?> params) {
        return new ApiException(code, messageKey, params);
    }

    public static ApiException forbidden(String messageKey) {
        return new ApiException(ErrorCode.FORBIDDEN, messageKey);
    }

    public static ApiException locked(ErrorCode code, String messageKey) {
        return new ApiException(code, messageKey);
    }

    public static ApiException locked(ErrorCode code, String messageKey, Map<String, ?> params) {
        return new ApiException(code, messageKey, params);
    }

    public static ApiException rateLimited(String messageKey) {
        return new ApiException(ErrorCode.RATE_LIMITED, messageKey);
    }

    public static ApiException validation(String messageKey, List<FieldErrorItem> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, messageKey, errors);
    }

    public static ApiException validation(String messageKey, Map<String, ?> params, List<FieldErrorItem> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, messageKey, params, errors);
    }

    /** A 422 with a code of its own ({@code entity_transition_not_allowed}, ADR-0032, 6.12) and its field problems. */
    public static ApiException unprocessable(
            ErrorCode code, String messageKey, Map<String, ?> params, List<FieldErrorItem> errors) {
        return new ApiException(code, messageKey, params, errors);
    }

    /** The refusal of a module's hook (ADR-0033, 3.2) as the error of the request. */
    public static ApiException refused(EntityRefusal refusal) {
        ErrorCode code = switch (refusal.kind()) {
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case CONFLICT -> ErrorCode.CONFLICT;
            case UNPROCESSABLE -> ErrorCode.VALIDATION_FAILED;
        };
        return new ApiException(code, refusal.messageKey(), refusal.params());
    }
}
