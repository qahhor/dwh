package com.smartup24.cms.instance.fnd.error;

import com.smartup24.cms.instance.common.error.ApiException;

/**
 * Нарушение ограничения БД или правила основы, переведённое в код (02 п.16; AC-9б).
 * Код — единственный контракт для вызывающего; SQL-текст доступен только как причина.
 * В ответе API — код {@link ConstraintErrorCode#errorCode()} и текст {@code error.fnd.<код>} (план 10/10, п. 3.1).
 */
public class ConstraintViolationException extends ApiException {

    private final ConstraintErrorCode code;

    public ConstraintViolationException(ConstraintErrorCode code, Throwable cause) {
        this(code);
        initCause(cause);
    }

    public ConstraintViolationException(ConstraintErrorCode code) {
        super(code.errorCode(), code.messageKey());
        this.code = code;
    }

    public ConstraintErrorCode code() {
        return code;
    }
}
