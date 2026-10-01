package com.smartup24.cms.instance.common.versioning;

import com.smartup24.cms.instance.common.error.ConstraintViolationException;

/** Optimistic locking: the row was changed by another client. The code is {@code stale_version}. */
public class StaleVersionException extends ConstraintViolationException {

    public StaleVersionException() {
        super(VersionError.STALE_VERSION);
    }
}
