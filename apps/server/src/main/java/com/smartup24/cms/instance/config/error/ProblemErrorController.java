package com.smartup24.cms.instance.config.error;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.ProblemDetailRecord;
import com.smartup24.cms.instance.common.error.ApiException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The servlet error page as RFC 9457 problem details (ADR-0021): what fails outside Spring MVC — an exception thrown
 * in a filter, a {@code sendError} of the container or a filter — reaches the client in the same shape as an error of
 * a handler, instead of Spring Boot's default {@code {timestamp, status, error, path}}. Replaces
 * {@code BasicErrorController}.
 */
@Hidden
@RestController
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ProblemErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

    private final ProblemMessages messages;

    /** A context without the i18n service (a web slice) renders from the packaged catalogs. */
    @Autowired
    public ProblemErrorController(ObjectProvider<ProblemMessages> messages) {
        this(messages.getIfAvailable(PackagedProblemMessages::new));
    }

    public ProblemErrorController(ProblemMessages messages) {
        this.messages = messages;
    }

    @RequestMapping
    public ResponseEntity<ProblemDetailRecord> error(HttpServletRequest request) {
        String uri = originalUri(request);
        Throwable failure = failure(request);
        HttpStatus status;
        ErrorCode code;
        String key;
        Map<String, Object> params;
        if (failure instanceof ApiException api && api.hasMessageKey()) {
            code = api.getErrorCode();
            status = HttpStatus.valueOf(code.getDefaultStatus());
            key = api.getMessageKey();
            params = api.getParams();
            log.warn("Request failed outside MVC at {}: {}", uri, api.getMessage());
        } else {
            status = status(request);
            code = codeOf(status);
            key = ApiException.defaultKey(code);
            params = Map.of();
            if (status.is5xxServerError()) {
                log.error("Request failed outside MVC at {} with {}", uri, status.value(), failure);
            } else {
                log.warn("Request answered {} outside MVC at {}", status.value(), uri);
            }
        }
        ProblemDetailRecord problem = ProblemDetailRecord.of(
                        code, key, params, messages.render(request, key, params), uri)
                .withStatus(status.value());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String originalUri(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return uri instanceof String original ? original : request.getRequestURI();
    }

    /** The exception of the failed request, unwrapped from the servlet exception a filter chain wraps it in. */
    private static @Nullable Throwable failure(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        Throwable failure = attribute instanceof Throwable thrown ? thrown : null;
        while (failure instanceof ServletException wrapped && wrapped.getCause() != null) {
            failure = wrapped.getCause();
        }
        return failure;
    }

    /** The status the container set for the error page; anything that is not an error status is a failure. */
    private static HttpStatus status(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
        return status != null && status.isError() ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** The code of a status the container or a filter answered; a status without its own code gets the family's. */
    static ErrorCode codeOf(HttpStatus status) {
        // By number: several statuses have two enum names (413, 422), and resolve() returns only one of them.
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 409 -> ErrorCode.CONFLICT;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> ErrorCode.VALIDATION_FAILED;
            case 428 -> ErrorCode.PRECONDITION_REQUIRED;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? ErrorCode.BAD_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
    }
}
