package com.smartup24.cms.instance.fnd.dwh;

import com.smartup24.cms.instance.fnd.error.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.error.ConstraintViolationException;

/**
 * The second database (pg-dwh) is unavailable. An empty list or {@code null} is never returned in place of data:
 * the calling OLTP transaction must roll back.
 */
public class DwhUnavailableException extends ConstraintViolationException {

    public DwhUnavailableException(Throwable cause) {
        super(ConstraintErrorCode.DWH_UNAVAILABLE, cause);
    }
}
