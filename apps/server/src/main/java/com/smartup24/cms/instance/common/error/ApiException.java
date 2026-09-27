package com.smartup24.cms.instance.common.error;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Base domain exception holding a typed ErrorCode.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final @Nullable List<FieldErrorItem> fieldErrors;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = null;
    }

    public ApiException(ErrorCode errorCode, String message, List<FieldErrorItem> fieldErrors) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public @Nullable List<FieldErrorItem> getFieldErrors() {
        return fieldErrors;
    }

    /** Refuses when a lookup found nothing: an existence check that keeps the "not found" of the caller. */
    public static void requirePresent(Optional<?> lookup, Supplier<ApiException> error) {
        if (lookup.isEmpty()) {
            throw error.get();
        }
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(ErrorCode.UNAUTHORIZED, message);
    }

    public static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Неверный логин или пароль");
    }

    public static ApiException permissionDenied(String form, String action) {
        return new ApiException(
                ErrorCode.PERMISSION_DENIED, "Недостаточно прав для выполнения действия " + form + "." + action);
    }

    public static ApiException notFound(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    public static ApiException conflict(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    public static ApiException badRequest(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    public static ApiException forbidden(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException locked(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    public static ApiException rateLimited(String message) {
        return new ApiException(ErrorCode.RATE_LIMITED, message);
    }

    public static ApiException validation(String message, List<FieldErrorItem> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, errors);
    }
}
