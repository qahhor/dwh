package com.smartup24.cms.instance.config.error;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.error.ProblemDetailRecord;
import com.smartup24.cms.instance.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every error of a request into RFC 9457 problem details, {@code application/problem+json} (plan 10/10, item
 * 3.1): the code, the catalog key of the text with its parameters, and the text rendered in the request's language.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The multipart limit of the application (spring.servlet.multipart.max-file-size). */
    private static final int UPLOAD_LIMIT_MEGABYTES = 50;

    private final ProblemMessages messages;

    /** A context without the i18n service (a web slice) renders from the packaged catalogs. */
    @Autowired
    public GlobalExceptionHandler(ObjectProvider<ProblemMessages> messages) {
        this(messages.getIfAvailable(PackagedProblemMessages::new));
    }

    public GlobalExceptionHandler(ProblemMessages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetailRecord> handleApiException(ApiException ex, HttpServletRequest request) {
        String key = ex.getMessageKey();
        Map<String, Object> params = ex.getParams();
        if (!ex.hasMessageKey()) {
            // Every error names a catalog key (ADR-0021); a sentence is a defect of the caller and never reaches the
            // client: the code's own text goes out instead.
            log.error("ApiException without a catalog key at {}: {}", request.getRequestURI(), key);
            key = ApiException.defaultKey(ex.getErrorCode());
            params = Map.of();
        }
        String detail = messages.render(request, key, params);

        var problem = ex.getFieldErrors() != null && !ex.getFieldErrors().isEmpty()
                ? ProblemDetailRecord.ofValidation(
                        key, params, detail, request.getRequestURI(), rendered(request, ex.getFieldErrors()))
                : ProblemDetailRecord.of(ex.getErrorCode(), key, params, detail, request.getRequestURI());
        return respond(HttpStatus.valueOf(ex.getErrorCode().getDefaultStatus()), problem);
    }

    /** The field errors with the text of each keyed one rendered in the request's language, as {@code detail} is. */
    private List<FieldErrorItem> rendered(HttpServletRequest request, List<FieldErrorItem> errors) {
        return errors.stream()
                .map(error -> error.messageKey() == null
                        ? error
                        : error.withMessage(messages.render(
                                request, error.messageKey(), error.params() == null ? Map.of() : error.params())))
                .toList();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetailRecord> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Некорректный JSON — вина клиента, а не сервера: 400, не 500.
        // Текст исключения наружу не отдаём (может содержать фрагменты тела).
        log.warn("Некорректное тело запроса {}: {}", request.getRequestURI(), ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "error.request_body_invalid", Map.of(), request);
    }

    /**
     * Д-9 (AUDIT-05): метод не поддержан маршрутом — это ошибка клиента, а не сбой сервера.
     * Раньше исключение проваливалось в общий обработчик: клиент получал 500, а в журнал
     * шло «Unhandled exception», маскируя настоящие сбои.
     * RFC 9110 требует на 405 заголовок Allow — отдаём его, чтобы клиент знал разрешённые методы.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetailRecord> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.warn("Метод {} не поддержан маршрутом {}", ex.getMethod(), request.getRequestURI());

        var response = problem(
                HttpStatus.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED,
                "error.method_not_allowed_method",
                Map.of("method", ex.getMethod()),
                request);
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            String allow = supported.stream().map(HttpMethod::name).collect(Collectors.joining(", "));
            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .header("Allow", allow)
                    .body(response.getBody());
        }
        return response;
    }

    /**
     * Нарушение ограничения БД (unique, not null, внешний ключ) — следствие
     * данных запроса, а не сбоя сервера. Раньше уходило в общий обработчик:
     * клиент получал 500, а в журнал шло «Unhandled exception».
     *
     * Наружу идёт только код и общий текст: имя ограничения и фрагмент SQL —
     * внутренняя деталь схемы, по которой не должен строиться клиент.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetailRecord> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn(
                "Нарушение ограничения целостности на {}: {}",
                request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());

        ErrorCode code = ex instanceof DuplicateKeyException ? ErrorCode.CODE_ALREADY_EXISTS : ErrorCode.CONFLICT;
        return problem(HttpStatus.CONFLICT, code, "error.integrity_violation", Map.of(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetailRecord> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.warn("Upload rejected because it exceeds the configured size boundary: {}", request.getRequestURI());
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCode.FILE_SIZE_EXCEEDED,
                "error.file_size_exceeded_limit",
                Map.of("megabytes", UPLOAD_LIMIT_MEGABYTES),
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetailRecord> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "error.not_found", Map.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetailRecord> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldErrorItem(fe.getField(), fe.getCode(), fe.getDefaultMessage()));
        }

        String key = "error.validation_failed";
        var problem = ProblemDetailRecord.ofValidation(
                key, Map.of(), messages.render(request, key, Map.of()), request.getRequestURI(), errors);
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /** Constraints on method parameters (a list body with checked elements): a client error, like a bean's. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetailRecord> handleMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldErrorItem> errors = new ArrayList<>();
        ex.getParameterValidationResults()
                .forEach(result -> result.getResolvableErrors()
                        .forEach(error -> errors.add(new FieldErrorItem(
                                result.getMethodParameter().getParameterName() == null
                                        ? "body"
                                        : result.getMethodParameter().getParameterName(),
                                error.getCodes() == null || error.getCodes().length == 0
                                        ? "invalid"
                                        : error.getCodes()[error.getCodes().length - 1],
                                error.getDefaultMessage()))));
        String key = "error.validation_failed";
        var problem = ProblemDetailRecord.ofValidation(
                key, Map.of(), messages.render(request, key, Map.of()), request.getRequestURI(), errors);
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetailRecord> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.warn(
                "Некорректный тип аргумента в запросе {}: параметр '{}' имеет значение '{}'",
                request.getRequestURI(),
                ex.getName(),
                ex.getValue());
        return problem(
                HttpStatus.BAD_REQUEST,
                ErrorCode.BAD_REQUEST,
                "error.request_param_invalid",
                Map.of("name", ex.getName()),
                request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetailRecord> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        log.warn("Отсутствует обязательный параметр запроса {}: '{}'", request.getRequestURI(), ex.getParameterName());
        return problem(
                HttpStatus.BAD_REQUEST,
                ErrorCode.BAD_REQUEST,
                "error.request_param_missing",
                Map.of("name", ex.getParameterName()),
                request);
    }

    /** A body in a type the route does not read: 415 with the types it does read in {@code Accept} (RFC 9110). */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetailRecord> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        log.warn("Unsupported content type {} at {}", ex.getContentType(), request.getRequestURI());
        String type = ex.getContentType() == null ? "" : ex.getContentType().toString();
        var response = problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "error.request_media_type_unsupported",
                Map.of("contentType", type),
                request);
        if (ex.getSupportedMediaTypes().isEmpty()) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Accept", MediaType.toString(ex.getSupportedMediaTypes()))
                .body(response.getBody());
    }

    /** The route cannot answer in any type the caller accepts: 406, the problem itself still as problem+json. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetailRecord> handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        log.warn("No acceptable representation at {}: {}", request.getRequestURI(), request.getHeader("Accept"));
        return problem(HttpStatus.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE, "error.not_acceptable", Map.of(), request);
    }

    /** A multipart request without a required part (an upload without its file): the client's error. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetailRecord> handleMissingPart(
            MissingServletRequestPartException ex, HttpServletRequest request) {
        log.warn("Missing request part at {}: '{}'", request.getRequestURI(), ex.getRequestPartName());
        return problem(
                HttpStatus.BAD_REQUEST,
                ErrorCode.BAD_REQUEST,
                "error.request_part_missing",
                Map.of("name", ex.getRequestPartName()),
                request);
    }

    /** A request an upload route cannot read as multipart (not multipart at all, or broken): the client's error. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetailRecord> handleMultipart(MultipartException ex, HttpServletRequest request) {
        log.warn("Unreadable multipart request at {}: {}", request.getRequestURI(), ex.getMessage());
        return problem(
                HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "error.request_multipart_invalid", Map.of(), request);
    }

    /** A required header or cookie is missing; other binding failures of the request are the client's error too. */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ProblemDetailRecord> handleBinding(
            ServletRequestBindingException ex, HttpServletRequest request) {
        if (ex instanceof MissingPathVariableException missing && !missing.isMissingAfterConversion()) {
            // A route whose pattern lacks the variable its handler reads: a defect of the server, not of the call.
            return handleGenericException(ex, request);
        }
        log.warn("Request binding failed at {}: {}", request.getRequestURI(), ex.getMessage());
        if (ex instanceof MissingRequestHeaderException missing) {
            return problem(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.BAD_REQUEST,
                    "error.request_header_missing",
                    Map.of("name", missing.getHeaderName()),
                    request);
        }
        if (ex instanceof MissingRequestCookieException missing) {
            return problem(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.BAD_REQUEST,
                    "error.request_cookie_missing",
                    Map.of("name", missing.getCookieName()),
                    request);
        }
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "error.bad_request", Map.of(), request);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        // Браузер закрыл SSE/HTTP-соединение: ответ уже недоступен, формировать 500 поздно и неверно.
        log.debug("Клиент закрыл соединение до завершения ответа: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetailRecord> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "error.internal_error", Map.of(), request);
    }

    private ResponseEntity<ProblemDetailRecord> problem(
            HttpStatus status, ErrorCode code, String key, Map<String, Object> params, HttpServletRequest request) {
        var problem = ProblemDetailRecord.of(
                code, key, params, messages.render(request, key, params), request.getRequestURI());
        return respond(status, problem);
    }

    private static ResponseEntity<ProblemDetailRecord> respond(HttpStatus status, ProblemDetailRecord problem) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem.withStatus(status.value()));
    }
}
