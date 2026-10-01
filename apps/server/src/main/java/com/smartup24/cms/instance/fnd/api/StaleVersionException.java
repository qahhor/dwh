package com.smartup24.cms.instance.fnd.api;

/** Optimistic locking: the row was changed by another client. The code is {@code stale_version}. */
public class StaleVersionException extends ConstraintViolationException {

    public StaleVersionException() {
        super(ConstraintErrorCode.STALE_VERSION);
    }
}
