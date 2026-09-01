package com.greenwhite.dwh.cp.error;

import com.greenwhite.dwh.cp.instance.CpInstanceRequestGuardFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CpProblemDetailsHandler {

    private static final Logger log = LoggerFactory.getLogger(CpProblemDetailsHandler.class);

    @ExceptionHandler(CpApiException.class)
    ResponseEntity<ProblemBody> handleApi(CpApiException error, HttpServletRequest request) {
        return problem(error.status(), error.errorCode(), error.getMessage(), request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class
    })
    ResponseEntity<ProblemBody> handleValidation(Exception error, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Request validation failed",
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemBody> handleMalformed(HttpMessageNotReadableException error,
                                                HttpServletRequest request) {
        if (hasCause(error, CpInstanceRequestGuardFilter.PayloadTooLargeException.class)) {
            return problem(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "instance_payload_too_large",
                    "Instance request body exceeds the configured limit",
                    request);
        }
        return problem(
                HttpStatus.BAD_REQUEST,
                "request_malformed",
                "Request body is malformed or contains unknown fields",
                request);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> expected) {
        Throwable current = error;
        while (current != null) {
            if (expected.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemBody> handleStatus(ResponseStatusException error,
                                             HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String detail = error.getReason() != null ? error.getReason() : status.getReasonPhrase();
        return problem(status, "http_" + status.value(), detail, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemBody> handleUnhandled(Exception error, HttpServletRequest request) {
        log.error("Unhandled API failure [type={}, path={}]",
                error.getClass().getSimpleName(),
                request.getRequestURI());
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error",
                "An internal error occurred",
                request);
    }

    private static ResponseEntity<ProblemBody> problem(HttpStatus status,
                                                       String errorCode,
                                                       String detail,
                                                       HttpServletRequest request) {
        Object traceId = request.getAttribute(CpRequestTraceFilter.TRACE_ID_ATTRIBUTE);
        ProblemBody body = new ProblemBody(
                "https://api.dwh.internal/errors/" + errorCode,
                status.getReasonPhrase(),
                status.value(),
                errorCode,
                detail,
                request.getRequestURI(),
                traceId != null ? traceId.toString() : "");
        return ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    record ProblemBody(
            String type,
            String title,
            int status,
            String errorCode,
            String detail,
            String instance,
            String traceId) {
    }
}
