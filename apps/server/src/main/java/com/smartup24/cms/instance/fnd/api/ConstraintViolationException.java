package com.smartup24.cms.instance.fnd.api;

import com.smartup24.cms.instance.common.error.ApiException;

/**
 * A database constraint violation or a foundation rule violation, translated into a code. The code is the only
 * contract for the caller; the SQL text is available only as the cause. The API response carries the code
 * {@link ConstraintErrorCode#errorCode()} and the message {@code error.fnd.<code>} (plan 10/10, item 3.1).
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
