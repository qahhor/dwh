package com.smartup24.cms.core.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable representation of RFC 9457 Problem Details for HTTP APIs.
 *
 * <p>{@code code} is the machine-readable contract. {@code messageKey} and {@code params} name the text in the i18n
 * catalogs (plan 10/10, item 3.1), so a client in another language can render it itself; {@code detail} is that text
 * already rendered by the server in the request's language.
 *
 * <p>{@code type} is {@link #TYPE_PREFIX} followed by the code (ADR-0021, addendum of 2026-10-01): an identifier, not
 * an address to fetch; clients branch on {@code code}.
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
        List<FieldErrorItem> errors,
        String messageKey,
        Map<String, Object> params) {

    /** The prefix of every problem type URI (plan 10/10, item 4.7). */
    public static final String TYPE_PREFIX = "urn:smartupcms:problem:";

    /** The problem type URI of {@code code}. */
    public static String typeOf(String code) {
        return TYPE_PREFIX + code;
    }

    public static ProblemDetailRecord of(ErrorCode errorCode, String detail, String instance) {
        return of(errorCode, null, null, detail, instance);
    }

    public static ProblemDetailRecord of(
            ErrorCode errorCode, String messageKey, Map<String, Object> params, String detail, String instance) {
        return new ProblemDetailRecord(
                typeOf(errorCode.getCode()),
                errorCode.name(),
                errorCode.getDefaultStatus(),
                errorCode.getCode(),
                detail,
                instance,
                Instant.now(),
                null,
                messageKey,
                params == null || params.isEmpty() ? null : Map.copyOf(params));
    }

    /** The same problem with the status of the response it goes out with (RFC 9457: they must agree). */
    public ProblemDetailRecord withStatus(int httpStatus) {
        return httpStatus == status
                ? this
                : new ProblemDetailRecord(
                        type, title, httpStatus, code, detail, instance, timestamp, errors, messageKey, params);
    }

    public static ProblemDetailRecord ofValidation(String detail, String instance, List<FieldErrorItem> errors) {
        return ofValidation(null, null, detail, instance, errors);
    }

    public static ProblemDetailRecord ofValidation(
            String messageKey,
            Map<String, Object> params,
            String detail,
            String instance,
            List<FieldErrorItem> errors) {
        return new ProblemDetailRecord(
                typeOf(ErrorCode.VALIDATION_FAILED.getCode()),
                "Validation Failed",
                422,
                ErrorCode.VALIDATION_FAILED.getCode(),
                detail,
                instance,
                Instant.now(),
                errors,
                messageKey,
                params == null || params.isEmpty() ? null : Map.copyOf(params));
    }
}
