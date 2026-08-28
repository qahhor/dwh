package com.greenwhite.dwh.core.error;

/**
 * Standard machine-readable error codes across DWH Platform according to TRD-04 Section 8.
 */
public enum ErrorCode {
    // 400 Bad Request
    BAD_REQUEST("bad_request", 400),
    RESET_CODE_INVALID("reset_code_invalid", 400),
    RESET_CODE_EXPIRED("reset_code_expired", 400),
    INVITE_INVALID("invite_invalid", 400),
    INVITE_EXPIRED("invite_expired", 400),
    FILE_CORRUPTED("file_corrupted", 400),
    CODE_ALREADY_EXISTS("code_already_exists", 400),
    INVALID_URL("invalid_url", 400),
    WEBHOOK_TARGET_UNREACHABLE("webhook_target_unreachable", 400),

    // 401 Unauthorized
    UNAUTHORIZED("unauthorized", 401),
    INVALID_CREDENTIALS("invalid_credentials", 401),
    OTP_INVALID("otp_invalid", 401),
    OTP_EXPIRED("otp_expired", 401),
    SESSION_EXPIRED("session_expired", 401),
    SESSION_REVOKED("session_revoked", 401),
    TOKEN_REVOKED("token_revoked", 401),

    // 403 Forbidden
    FORBIDDEN("forbidden", 403),
    PERMISSION_DENIED("permission_denied", 403),
    USER_BLOCKED("user_blocked", 403),
    SUPERADMIN_IMMUTABLE("superadmin_immutable", 403),
    LICENSE_READ_ONLY("license_read_only", 403),

    // 404 Not Found
    NOT_FOUND("not_found", 404),
    USER_NOT_FOUND("user_not_found", 404),
    ROLE_NOT_FOUND("role_not_found", 404),
    PROJECT_NOT_FOUND("project_not_found", 404),
    TASK_NOT_FOUND("task_not_found", 404),
    FILE_NOT_FOUND("file_not_found", 404),

    // 409 Conflict
    CONFLICT("conflict", 409),
    STATUS_TRANSITION_FORBIDDEN("status_transition_forbidden", 409),
    TASK_PARENT_CYCLE("task_parent_cycle", 409),
    SINGLE_RESPONSIBLE_VIOLATION("single_responsible_violation", 409),
    FIELD_IN_USE("field_in_use", 409),

    // 413 Payload Too Large & 415 Unsupported Media Type
    FILE_SIZE_EXCEEDED("file_size_exceeded", 413),
    FILE_TYPE_FORBIDDEN("file_type_forbidden", 415),

    // 422 Unprocessable Entity
    VALIDATION_FAILED("validation_failed", 422),
    PASSWORD_POLICY("password_policy", 422),
    EMPTY_QUERY("empty_query", 422),

    // 423 Locked
    LOGIN_LOCKED("login_locked", 423),
    OTP_ATTEMPTS_EXCEEDED("otp_attempts_exceeded", 423),

    // 429 Too Many Requests
    RATE_LIMITED("rate_limited", 429),
    OTP_RATE_LIMITED("otp_rate_limited", 429),

    // 500 Internal Error
    INTERNAL_ERROR("internal_error", 500),
    SERVICE_UNAVAILABLE("service_unavailable", 503);

    private final String code;
    private final int defaultStatus;

    ErrorCode(String code, int defaultStatus) {
        this.code = code;
        this.defaultStatus = defaultStatus;
    }

    public String getCode() {
        return code;
    }

    public int getDefaultStatus() {
        return defaultStatus;
    }
}
