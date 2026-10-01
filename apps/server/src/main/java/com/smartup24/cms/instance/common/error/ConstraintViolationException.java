package com.smartup24.cms.instance.common.error;

/**
 * A database constraint violation or a module rule violation, translated into a code. The code is the only contract
 * for the caller; the SQL text is available only as the cause. The API response carries the code
 * {@link ConstraintCode#errorCode()} and the message {@link ConstraintCode#messageKey()} (plan 10/10, item 3.1).
 */
public class ConstraintViolationException extends ApiException {

    private final ConstraintCode code;

    public ConstraintViolationException(ConstraintCode code, Throwable cause) {
        this(code);
        initCause(cause);
    }

    public ConstraintViolationException(ConstraintCode code) {
        super(code.errorCode(), code.messageKey());
        this.code = code;
    }

    public ConstraintCode code() {
        return code;
    }
}
