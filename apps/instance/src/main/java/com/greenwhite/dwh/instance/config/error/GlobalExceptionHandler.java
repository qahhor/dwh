package com.greenwhite.dwh.instance.config.error;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.core.error.ProblemDetailRecord;
import com.greenwhite.dwh.instance.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

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
