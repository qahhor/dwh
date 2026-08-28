package com.greenwhite.dwh.core.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Immutable representation of RFC 9457 Problem Details for HTTP APIs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetailRecord(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String instance,
        Instant timestamp,
        List<FieldErrorItem> errors
) {
    public static ProblemDetailRecord of(ErrorCode errorCode, String detail, String instance) {
        return new ProblemDetailRecord(
                "https://api.dwh.internal/errors/" + errorCode.getCode(),
                errorCode.name(),
                errorCode.getDefaultStatus(),
                errorCode.getCode(),
                detail,
                instance,
                Instant.now(),
                null
        );
    }

    public static ProblemDetailRecord ofValidation(String detail, String instance, List<FieldErrorItem> errors) {
        return new ProblemDetailRecord(
                "https://api.dwh.internal/errors/" + ErrorCode.VALIDATION_FAILED.getCode(),
                "Validation Failed",
                422,
                ErrorCode.VALIDATION_FAILED.getCode(),
                detail,
                instance,
                Instant.now(),
                errors
        );
    }
}
