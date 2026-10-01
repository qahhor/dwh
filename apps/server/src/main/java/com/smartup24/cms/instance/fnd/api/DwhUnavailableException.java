package com.smartup24.cms.instance.fnd.api;

import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import com.smartup24.cms.instance.common.error.TransientFailure;
import com.smartup24.cms.instance.warehouse.api.WarehouseError;

/**
 * The second database (pg-dwh) is unavailable. An empty list or {@code null} is never returned in place of data:
 * the calling OLTP transaction must roll back.
 */
public class DwhUnavailableException extends ConstraintViolationException implements TransientFailure {

    public DwhUnavailableException(Throwable cause) {
        super(WarehouseError.DWH_UNAVAILABLE, cause);
    }
}
