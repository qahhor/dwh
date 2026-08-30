package com.greenwhite.dwh.instance.config.error;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.core.error.ProblemDetailRecord;
import com.greenwhite.dwh.instance.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetailRecord> handleApiException(ApiException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getErrorCode().getDefaultStatus());

        var problem = ex.getFieldErrors() != null && !ex.getFieldErrors().isEmpty()
                ? ProblemDetailRecord.ofValidation(ex.getMessage(), request.getRequestURI(), ex.getFieldErrors())
                : ProblemDetailRecord.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI());

        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetailRecord> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                    HttpServletRequest request) {
        // Некорректный JSON — вина клиента, а не сервера: 400, не 500.
        // Текст исключения наружу не отдаём (может содержать фрагменты тела).
        log.warn("Некорректное тело запроса {}: {}", request.getRequestURI(), ex.getMessage());
        var problem = ProblemDetailRecord.of(ErrorCode.BAD_REQUEST,
                "Некорректный формат тела запроса", request.getRequestURI());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Д-9 (AUDIT-05): метод не поддержан маршрутом — это ошибка клиента, а не сбой сервера.
     * Раньше исключение проваливалось в общий обработчик: клиент получал 500, а в журнал
     * шло «Unhandled exception», маскируя настоящие сбои.
     * RFC 9110 требует на 405 заголовок Allow — отдаём его, чтобы клиент знал разрешённые методы.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetailRecord> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                        HttpServletRequest request) {
        log.warn("Метод {} не поддержан маршрутом {}", ex.getMethod(), request.getRequestURI());

        var problem = ProblemDetailRecord.of(ErrorCode.METHOD_NOT_ALLOWED,
                "Метод " + ex.getMethod() + " не поддерживается этим ресурсом",
                request.getRequestURI());

        var builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            String allow = supported.stream().map(HttpMethod::name).collect(Collectors.joining(", "));
            return builder.header("Allow", allow).body(problem);
        }
        return builder.body(problem);
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
    public ResponseEntity<ProblemDetailRecord> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                            HttpServletRequest request) {
        log.warn("Нарушение ограничения целостности на {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());

        ErrorCode code = ex instanceof DuplicateKeyException ? ErrorCode.CODE_ALREADY_EXISTS : ErrorCode.CONFLICT;
        var problem = ProblemDetailRecord.of(code,
                "Запрос нарушает ограничение целостности данных", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetailRecord> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        var problem = ProblemDetailRecord.of(ErrorCode.NOT_FOUND, "Ресурс не найден", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetailRecord> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldErrorItem(fe.getField(), fe.getCode(), fe.getDefaultMessage()));
        }

        var problem = ProblemDetailRecord.ofValidation(
                "Ошибка валидации входных данных",
                request.getRequestURI(),
                errors
        );

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetailRecord> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        log.warn("Некорректный тип аргумента в запросе {}: параметр '{}' имеет значение '{}'",
                request.getRequestURI(), ex.getName(), ex.getValue());
        var problem = ProblemDetailRecord.of(
                ErrorCode.BAD_REQUEST,
                "Некорректный параметр запроса: " + ex.getName(),
                request.getRequestURI()
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetailRecord> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        log.warn("Отсутствует обязательный параметр запроса {}: '{}'", request.getRequestURI(), ex.getParameterName());
        var problem = ProblemDetailRecord.of(
                ErrorCode.BAD_REQUEST,
                "Отсутствует обязательный параметр запроса: " + ex.getParameterName(),
                request.getRequestURI()
        );
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        // Браузер закрыл SSE/HTTP-соединение: ответ уже недоступен, формировать 500 поздно и неверно.
        log.debug("Клиент закрыл соединение до завершения ответа: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetailRecord> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);

        var problem = ProblemDetailRecord.of(
                ErrorCode.INTERNAL_ERROR,
                "Внутренняя ошибка сервера. Обратитесь к администратору.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
